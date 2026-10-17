package net.imadz.m25.component

import net.imadz.monarch.persistence.{PersistentMonarch, RunObserver}

import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import akka.persistence.typed.PersistenceId

import scala.concurrent.ExecutionContext

/**
 * Bank-flavored facade over `PersistentMonarch` (the `monarch-persistence` library).
 *
 * The hand-rolled `EventSourcedBehavior` that used to live here (~400 lines) is now
 * `net.imadz.monarch.persistence.PersistentMonarch`: per-stage `StageDone`
 * journaling with state snapshots, cursor-based resume on `RecoveryCompleted`,
 * and `RunRegistry` generation guards. This object only holds the bank-domain
 * wiring: stage queue, interpreter, protobuf state codec, sharding bootstrap.
 *
 * Concrete `[String, String]` types: protobuf needs a stable schema, so the
 * demo commits to `String` items (the `Any`-typed flexibility was only ever
 * exercised with Strings here).
 *
 * Protocol is `PersistentMonarch`'s: `StartRun(runId, initialState, replyTo)`.
 * Use [[startRun]] to build the initial `BankChainState` from a batch.
 */
object ChainExecutionActor {

  type BankState = BankChainState[String, String]
  type BankCommand = PersistentMonarch.Command[BankState]
  type BankReply = PersistentMonarch.RunReply

  /** Persistent behavior for one bank chain. One entity per run. */
  def behavior(
      persistenceId: PersistenceId,
      pipeline: SubBatchPipeline[String, String],
      observer: RunObserver[BankState] = RunObserver.nop[BankState]
  )(implicit ec: ExecutionContext): Behavior[BankCommand] =
    PersistentMonarch.behavior[BankStage, BankState](
      persistenceId = persistenceId,
      stages = BankStage.chain,
      interpreter = (stage, state) => BankChain.runStage(stage, state, pipeline),
      stateCodec = BankStateCodec.protobuf,
      stageName = BankStage.stageName,
      observer = observer
    )

  /** Cluster sharding bootstrap: entityId == runId, journal is per-entity. */
  def initSharding(
      sharding: ClusterSharding,
      pipelineFor: String => SubBatchPipeline[String, String],
      observerFor: String => RunObserver[BankState] = _ => RunObserver.nop[BankState]
  )(implicit ec: ExecutionContext): EntityTypeKey[BankCommand] =
    PersistentMonarch.initSharding(
      sharding,
      "m25-chain-executor",
      entityId =>
        behavior(
          PersistenceId("m25-chain-executor", entityId),
          pipelineFor(entityId),
          observerFor(entityId))
    )

  /** Build the initial `BankChainState` from a batch and start the run. */
  def startRun(
      ref: ActorRef[BankCommand],
      batchId: String,
      chainId: String,
      items: Seq[String],
      replyTo: ActorRef[BankReply]
  ): Unit = {
    val initial: BankState = BankChainState[String, String](
      batchId = batchId,
      chainId = chainId,
      items = items,
      context = Map("batchId" -> batchId, "chainId" -> chainId))
    ref ! PersistentMonarch.StartRun(s"$chainId-$batchId", initial, replyTo)
  }
}
