package com.github.pshirshov.izumi.logstage.api

import com.github.pshirshov.izumi.fundamentals.platform.jvm.SourceFilePosition
import com.github.pshirshov.izumi.logstage.api.Log._
import com.github.pshirshov.izumi.logstage.api.rendering.{RenderingOptions, StringRenderingPolicy}
import org.scalatest.WordSpec

import scala.util.Random

class BasicLoggingTest extends WordSpec {

  "Argument extraction macro" should {
    "extract argument names from an arbitrary string" in {
      val arg1 = 1
      val arg2 = "argument 2"

      import ExtratingStringInterpolator._

      val message = m"argument1: $arg1, argument2: $arg2, argument2 again: $arg2, expression ${2+2}, ${2+2}"
      assert(message.args == List(LogArg("arg1",1), LogArg("arg2","argument 2"), LogArg("arg2","argument 2"), LogArg("UNNAMED:4",4), LogArg("UNNAMED:4",4)))
      assert(message.template.parts == List("argument1: ", ", argument2: ", ", argument2 again: ", ", expression ", ", ", ""))

      val message1 = m"expression: ${Random.self.nextInt()+1}"
      assert(message1.args.head.name == "EXPRESSION:scala.util.Random.self.nextInt().+(1)")
      assert(message1.template.parts == List("expression: ", ""))
    }
  }

  "String rendering policy" should {
    "not fail on unbalanced messages" in {
      val p = new StringRenderingPolicy(RenderingOptions(withColors = false))
      val rendered = render(p, Message(StringContext("begin ", " end"), Seq(LogArg("[a1]", 1), LogArg("[a2]", 2))))
      assert(rendered.endsWith("begin [a1]=1 end; [a2]=2"))
    }
  }

  private def render(p: StringRenderingPolicy, m: Message) = {
    p.render(Entry(m, Context(StaticExtendedContext(LoggerId("test"), SourceFilePosition("test.scala", 0)), DynamicContext(Level.Warn, ThreadData("test", 0), 0), CustomContext(Seq.empty))))
  }
}


