package boopickle.perftests

import boopickle.BufferPool
import org.scalajs.dom
import org.scalajs.dom.html

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel, JSGlobal, JSName}
import scala.scalajs.js.typedarray.Uint8Array
import scalatags.JsDom.all._

@JSGlobal("Zlib.Gzip")
@js.native
class Gzip(data: js.Array[Byte]) extends js.Object {
  def compress(): Uint8Array = js.native
}

@JSExportTopLevel("BooApp")
object BooApp {

  import scala.scalajs.js.JSConverters._

  def runTests(resultsDiv: html.Div) = {
    val resView = pre.render
    resultsDiv.innerHTML = ""
    resultsDiv.appendChild(div(cls := "bold", s"Running ${Tests.suites.size} tests").render)
    resultsDiv.appendChild(resView)
    def runNext(suites: Seq[PerfTestSuite]): Unit = {
      val suite  = suites.head
      val header = s"${1 + Tests.suites.size - suites.size}/${Tests.suites.size} : ${suite.name}"
      resView.innerHTML = resView.innerHTML + header + "\n"
      resView.innerHTML = resView.innerHTML + "=" * header.length + "\n"
      resView.innerHTML =
        resView.innerHTML + f"${"Library"}%-10s ${"ops/s"}%-10s ${"%"}%-10s ${"size"}%-10s ${"%"}%-10s ${"size.gz"}%-10s ${"%"}%-10s" + "\n"
      val tester = new PerfTester(suite)
      val res    = tester.runSuite
      // zip result data to see how small it gets
      val resSizes = res.results.map { r =>
        val rawSize = r.data.length
        val gz      = new Gzip(r.data.toJSArray)
        (r, rawSize, gz.compress().length)
      }
      val maxCount  = resSizes.map(_._1.count).max
      val minSize   = resSizes.map(_._2).min
      val minGZSize = resSizes.map(_._3).min
      resSizes.foreach { r =>
        resView.innerHTML =
          resView.innerHTML + f"${r._1.name}%-10s ${r._1.count}%-10d ${f"${r._1.count * 100.0 / maxCount}%.1f%%"}%-10s ${r._2}%-10d ${f"${r._2 * 100.0 / minSize}%.0f%%"}%-10s ${r._3}%-10d ${f"${r._3 * 100.0 / minGZSize}%.0f%%"}%-10s" + "\n"
      }
      resView.innerHTML = resView.innerHTML + "\n"
      if (suites.tail.nonEmpty)
        dom.window.setTimeout(() => runNext(suites.tail), 100)
      else {
        resultsDiv.appendChild(h4("Completed!").render)
      }
      // print out buffer pool usage
      println(s"""BufferPool:
            |  allocations = ${BufferPool.allocOk}
            |  misses      = ${BufferPool.allocMiss}
            """.stripMargin)
      ()
    }
    dom.window.setTimeout(() => runNext(Tests.suites), 10)
  }

  @JSExport
  def main(): Unit = {
    val contentRoot = dom.document.getElementById("contentRoot")
    val runButton   = button(cls := "waves-effect waves-light btn", i(cls := "mdi-av-play-arrow right"), "Run tests").render
    val results     = div(cls := "row").render
    runButton.onclick = (e: dom.Event) => runTests(results)

    contentRoot.appendChild(div(cls := "row", runButton).render)
    contentRoot.appendChild(results)
    ()
  }
}
