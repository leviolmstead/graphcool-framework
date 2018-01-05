package cool.graph.deploy.migration.migrator

import akka.actor.{Actor, Stash}
import cool.graph.deploy.database.persistence.MigrationPersistence
import cool.graph.deploy.migration.MigrationStepMapper
import cool.graph.deploy.migration.mutactions.ClientSqlMutaction
import cool.graph.deploy.schema.DeploymentInProgress
import cool.graph.shared.models.{Migration, MigrationStatus, MigrationStep, Schema}
import slick.jdbc.MySQLProfile.backend.DatabaseDef

import scala.concurrent.Future
import scala.util.{Failure, Success}

object DeploymentProtocol {
  object Initialize
  case class Schedule(projectId: String, nextSchema: Schema, steps: Vector[MigrationStep])
  object ResumeMessageProcessing
  object Ready
  object Deploy
}

/**
  * State machine states:
  *  - Initializing: Stashing all messages while initializing
  *  - Ready: Ready to schedule deployments and deploy
  *  - Busy: Currently deploying or scheduling, subsequent scheduling is rejected
  *
  * Transitions: Initializing -> Ready <-> Busy
  *
  * Why a state machine? Deployment should leverage futures for optimal performance, but there should only be one deployment
  * at a time for a given project and stage. Hence, processing is kicked off async and the actor changes behavior to reject
  * scheduling and deployment until the async processing restored the ready state.
  */
case class ProjectDeploymentActor(projectId: String, migrationPersistence: MigrationPersistence, clientDatabase: DatabaseDef) extends Actor with Stash {
  import DeploymentProtocol._

  implicit val ec          = context.system.dispatcher
  val stepMapper           = MigrationStepMapper(projectId)
  var activeSchema: Schema = _

  // Possible enhancement: Periodically scan the DB for migrations if signal was lost -> Wait and see if this is an issue at all
  // Possible enhancement: Migration retry in case of transient errors.

  initialize()

  def initialize() = {
    println(s"[Debug] Initializing deployment worker for $projectId")
    migrationPersistence.getLastMigration(projectId).map {
      case Some(migration) =>
        activeSchema = migration.schema
        migrationPersistence.getNextMigration(projectId).onComplete {
          case Success(migrationOpt) =>
            migrationOpt match {
              case Some(_) =>
                println(s"[Debug] Found unapplied migration for $projectId during init.")
                self ! Ready
                self ! Deploy

              case None =>
                self ! Ready
            }

          case Failure(err) =>
            println(s"Deployment worker initialization for project $projectId failed with $err")
            context.stop(self)
        }

      case None =>
        println(s"Deployment worker initialization for project $projectId failed: No current migration found for project.")
        context.stop(self)
    }
  }

  def receive: Receive = {
    case Ready =>
      context.become(ready)
      unstashAll()

    case _ =>
      stash()
  }

  def ready: Receive = {
    case msg: Schedule =>
      println(s"[Debug] Scheduling deployment for project $projectId")
      val caller = sender()
      context.become(busy) // Block subsequent scheduling and deployments
      handleScheduling(msg).onComplete {
        case Success(migration: Migration) =>
          caller ! migration
          self ! Deploy
          self ! ResumeMessageProcessing

        case Failure(err) =>
          self ! ResumeMessageProcessing
          caller ! akka.actor.Status.Failure(err)
      }

    case Deploy =>
      context.become(busy)
      handleDeployment().onComplete {
        case Success(_) =>
          println(s"[Debug] Applied migration for project $projectId")
          self ! ResumeMessageProcessing

        case Failure(err) =>
          println(s"[Debug] Error during deployment for project $projectId: $err")
          self ! ResumeMessageProcessing // todo Mark migration as failed
      }
  }

  def busy: Receive = {
    case _: Schedule =>
      sender() ! akka.actor.Status.Failure(DeploymentInProgress)

    case ResumeMessageProcessing =>
      context.become(ready)
      unstashAll()

    case _ =>
      stash()
  }

  def handleScheduling(msg: Schedule): Future[Migration] = {
    // Check if scheduling is possible (no pending migration), then create and return the migration
    migrationPersistence
      .getNextMigration(projectId)
      .transformWith {
        case Success(pendingMigrationOpt) =>
          pendingMigrationOpt match {
            case Some(_) => Future.failed(DeploymentInProgress)
            case None    => Future.unit
          }

        case Failure(err) =>
          Future.failed(err)
      }
      .flatMap { _ =>
        migrationPersistence.create(Migration(projectId, msg.nextSchema, msg.steps))
      }
  }

  def handleDeployment(): Future[Unit] = {
    // Need next project -> Load from DB or by migration
    // Get previous project from cache

    migrationPersistence.getNextMigration(projectId).transformWith {
      case Success(Some(nextMigration)) =>
        applyMigration(activeSchema, nextMigration).map { result =>
          if (result.succeeded) {
            activeSchema = nextMigration.schema
            migrationPersistence.updateMigrationStatus(nextMigration, MigrationStatus.Success)
          } else {
            migrationPersistence.updateMigrationStatus(nextMigration, MigrationStatus.RollbackFailure)
            Future.failed(new Exception("Applying migration failed."))
          }
        }

      case Failure(err) =>
        Future.failed(new Exception(s"Error while fetching migration: $err"))

      case Success(None) =>
        println("[Warning] Deployment signalled but no open migration found. Nothing to see here.")
        Future.unit
    }
  }

  def applyMigration(previousSchema: Schema, migration: Migration): Future[MigrationApplierResult] = {
    val initialProgress = MigrationProgress(pendingSteps = migration.steps, appliedSteps = Vector.empty, isRollingback = false)
    recurse(previousSchema, migration.schema, initialProgress)
  }

  def recurse(previousSchema: Schema, nextSchema: Schema, progress: MigrationProgress): Future[MigrationApplierResult] = {
    if (!progress.isRollingback) {
      recurseForward(previousSchema, nextSchema, progress)
    } else {
      recurseForRollback(previousSchema, nextSchema, progress)
    }
  }

  def recurseForward(previousSchema: Schema, nextSchema: Schema, progress: MigrationProgress): Future[MigrationApplierResult] = {
    if (progress.pendingSteps.nonEmpty) {
      val (step, newProgress) = progress.popPending

      val result = for {
        _ <- applyStep(previousSchema, nextSchema, step)
        x <- recurse(previousSchema, nextSchema, newProgress)
      } yield x

      result.recoverWith {
        case exception =>
          println("encountered exception while applying migration. will roll back.")
          exception.printStackTrace()
          recurseForRollback(previousSchema, nextSchema, newProgress.markForRollback)
      }
    } else {
      Future.successful(MigrationApplierResult(succeeded = true))
    }
  }

  def recurseForRollback(previousSchema: Schema, nextSchema: Schema, progress: MigrationProgress): Future[MigrationApplierResult] = {
    if (progress.appliedSteps.nonEmpty) {
      val (step, newProgress) = progress.popApplied

      for {
        _ <- unapplyStep(previousSchema, nextSchema, step).recover { case _ => () }
        x <- recurse(previousSchema, nextSchema, newProgress)
      } yield x
    } else {
      Future.successful(MigrationApplierResult(succeeded = false))
    }
  }

  def applyStep(previousSchema: Schema, nextSchema: Schema, step: MigrationStep): Future[Unit] = {
    stepMapper.mutactionFor(previousSchema, nextSchema, step).map(executeClientMutaction).getOrElse(Future.successful(()))
  }

  def unapplyStep(previousSchema: Schema, nextSchema: Schema, step: MigrationStep): Future[Unit] = {
    stepMapper.mutactionFor(previousSchema, nextSchema, step).map(executeClientMutactionRollback).getOrElse(Future.successful(()))
  }

  def executeClientMutaction(mutaction: ClientSqlMutaction): Future[Unit] = {
    for {
      statements <- mutaction.execute
      _          <- clientDatabase.run(statements.sqlAction)
    } yield ()
  }

  def executeClientMutactionRollback(mutaction: ClientSqlMutaction): Future[Unit] = {
    for {
      statements <- mutaction.rollback.get
      _          <- clientDatabase.run(statements.sqlAction)
    } yield ()
  }
}

case class MigrationProgress(
    appliedSteps: Vector[MigrationStep],
    pendingSteps: Vector[MigrationStep],
    isRollingback: Boolean
) {
  def addAppliedStep(step: MigrationStep) = copy(appliedSteps = appliedSteps :+ step)

  def popPending: (MigrationStep, MigrationProgress) = {
    val step = pendingSteps.head
    step -> copy(appliedSteps = appliedSteps :+ step, pendingSteps = pendingSteps.tail)
  }

  def popApplied: (MigrationStep, MigrationProgress) = {
    val step = appliedSteps.last
    step -> copy(appliedSteps = appliedSteps.dropRight(1))
  }

  def markForRollback = copy(isRollingback = true)
}

case class MigrationApplierResult(succeeded: Boolean)