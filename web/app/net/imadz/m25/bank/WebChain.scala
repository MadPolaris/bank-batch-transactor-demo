package net.imadz.m25.bank

import net.imadz.monarch.persistence.{PersistentMonarch, RunObserver}
import net.imadz.m25.component.{BankStage, ChainExecutionActor, SubBatchPipeline}

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import akka.persistence.typed.PersistenceId

import scala.concurrent.ExecutionContext

/**
 * Web 演示用的 entity wrapper：在 `PersistentMonarch` 外包一层，
 * 加一个 `SimulateCrash` 命令（演示按钮注入宕机）。
 *
 * Crash 语义：wrapper 连同内部的 monarch 一起停掉；分片在下一条消息到来时
 * 重启 entity，`PersistentMonarch` 从 journal 恢复（cursor-based resume，
 * 已完成的 stage 不重跑）。journal 不受影响。
 */
object WebChain {

  sealed trait Command
  final case class Wrapped(cmd: PersistentMonarch.Command[ChainExecutionActor.BankState]) extends Command
  case object SimulateCrash extends Command

  type WebReply = PersistentMonarch.RunReply

  def behavior(
      persistenceId: PersistenceId,
      pipeline: SubBatchPipeline[String, String],
      observer: RunObserver[ChainExecutionActor.BankState]
  )(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup { ctx =>
      val inner: ActorRef[PersistentMonarch.Command[ChainExecutionActor.BankState]] =
        ctx.spawn(
          ChainExecutionActor.behavior(persistenceId, pipeline, observer),
          "monarch"
        )
      Behaviors.receiveMessage {
        case Wrapped(cmd) =>
          inner ! cmd
          Behaviors.same
        case SimulateCrash =>
          ctx.log.warn("[web-demo] 宕机注入：entity 停止，journal 保留，重启后断点续跑")
          Behaviors.stopped
      }
    }

  def initSharding(
      sharding: ClusterSharding,
      pipelineFor: String => SubBatchPipeline[String, String],
      observerFor: String => RunObserver[ChainExecutionActor.BankState]
  )(implicit ec: ExecutionContext): EntityTypeKey[Command] = {
    val typeKey = EntityTypeKey[Command]("m25-bank-web-chain")
    sharding.init(
      akka.cluster.sharding.typed.scaladsl.Entity(typeKey)(ctx =>
        behavior(
          PersistenceId("m25-bank-web-chain", ctx.entityId),
          pipelineFor(ctx.entityId),
          observerFor(ctx.entityId)
        )
      )
    )
    typeKey
  }
}
