package com.wavesplatform.state2

import java.util.concurrent.TimeUnit

import com.wavesplatform.lang.{Parser, TypeChecker}
import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state2.StateSyntheticBenchmark._
import com.wavesplatform.utils.dummyTypeCheckerContext
import org.openjdk.jmh.annotations._
import org.scalacheck.Gen
import scorex.account.PrivateKeyAccount
import scorex.transaction.Transaction
import scorex.transaction.assets.{TransferTransaction, VersionedTransferTransaction}
import scorex.transaction.smart.{Script, SetScriptTransaction}

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
class StateSyntheticBenchmark {

  @Benchmark
  def appendBlock_test(db: St): Unit = db.genAndApplyNextBlock()

  @Benchmark
  def appendBlock_smart_test(db: SmartSt): Unit = db.genAndApplyNextBlock()

}

object StateSyntheticBenchmark {

  @State(Scope.Thread)
  class St extends BaseState {
    protected override def txGenP(sender: PrivateKeyAccount, ts: Long): Gen[Transaction] =
      for {
        amount    <- Gen.choose(1, waves(1))
        recipient <- accountGen
      } yield TransferTransaction.create(None, sender, recipient, amount, ts, None, 100000, Array.emptyByteArray).right.get
  }

  @State(Scope.Thread)
  class SmartSt extends BaseState {

    override protected def updateFunctionalitySettings(base: FunctionalitySettings): FunctionalitySettings = {
      base.copy(preActivatedFeatures = Map(4.toShort -> 0))
    }

    protected override def txGenP(sender: PrivateKeyAccount, ts: Long): Gen[Transaction] =
      for {
        recipient: PrivateKeyAccount <- accountGen
        amount                       <- Gen.choose(1, waves(1))
      } yield
        VersionedTransferTransaction
          .selfSigned(
            VersionedTransferTransaction.supportedVersions.head,
            None,
            sender,
            recipient.toAddress,
            amount,
            ts,
            300000,
            Array.emptyByteArray
          )
          .explicitGet()

    @Setup
    override def init(): Unit = {
      super.init()

      val textScript    = "sigVerify(tx.bodyBytes,tx.proof0,tx.senderPk)"
      val untypedScript = Parser(textScript).get.value
      val typedScript   = TypeChecker(dummyTypeCheckerContext, untypedScript).explicitGet()

      val setScriptBlock = nextBlock(
        Seq(
          SetScriptTransaction
            .selfSigned(
              SetScriptTransaction.supportedVersions.head,
              richAccount,
              Some(Script(typedScript)),
              100000,
              System.currentTimeMillis()
            )
            .explicitGet()
        )
      )

      applyBlock(setScriptBlock)
    }
  }

}
