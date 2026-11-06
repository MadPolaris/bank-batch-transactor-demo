package controllers

import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.util.{ByteString, Timeout}
import javax.inject.{Inject, Singleton}
import net.imadz.m25.bank.durable.{BankWorkflowProgram, LocalTaskClient}
import net.imadz.monarch.persistence.batch.{BatchProtocol, WorkflowHost}
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.mvc._
import play.filters.csrf.{CSRF, CSRFAddToken}

import java.util.Base64
import java.util.concurrent.{ArrayBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.concurrent.duration._
import scala.util.control.NonFatal

@Singleton
final class BankDurableWorkflowController @Inject()(cc: ControllerComponents, system: ActorSystem,
    configuration: Configuration, lifecycle: ApplicationLifecycle, addCsrfToken: CSRFAddToken)(implicit ec: ExecutionContext)
    extends AbstractController(cc) {
  private implicit val timeout: Timeout = Timeout(4.seconds)
  private implicit val scheduler: akka.actor.typed.Scheduler = system.toTyped.scheduler
  private val externalClient = new LocalTaskClient(configuration.get[Int]("bank.durable.external.port"))
  private val controlsEnabled = configuration.get[Boolean]("bank.durable.external.controls-enabled")
  require(java.nio.file.Paths.get(configuration.get[String]("bank.durable.data-dir")).isAbsolute,
    "bank.durable.data-dir must be an absolute, exclusively owned demo directory")
  private val threadIds = new AtomicInteger()
  private val executor = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue[Runnable](32), new ThreadFactory {
      override def newThread(task: Runnable): Thread = {
        val thread = new Thread(task, "bankdurable-external-" + threadIds.incrementAndGet())
        thread.setDaemon(true)
        thread
      }
    }, new ThreadPoolExecutor.AbortPolicy())
  private val externalIo = ExecutionContext.fromExecutorService(executor)
  lifecycle.addStopHook { () =>
    externalClient.close() // Also interrupts outstanding socket reads/writes.
    externalIo.shutdownNow()
    Future.successful(())
  }

  // Exactly one host owns this stable namespace in the application's existing ActorSystem.
  val workflowHost: WorkflowHost = new WorkflowHost(system.toTyped, "bankdurable",
    new BankWorkflowProgram(externalClient)(externalIo))

  private def guarded(action: => Future[Result]): Future[Result] = {
    def unavailable: Result = ServiceUnavailable(Json.obj("error" ->
      "Runtime or external I/O unavailable; outcome is unknown. Query state and retry only the same job/body."))
    try action.recover {
      case _: TimeoutException => GatewayTimeout(Json.obj("error" ->
        "No committed reply before the ask deadline; the command may have committed. Reuse the same job/body."))
      case NonFatal(_) => unavailable
    } catch { case NonFatal(_) => Future.successful(unavailable) }
  }

  private val runtimeOrigin = classOf[WorkflowHost].getProtectionDomain.getCodeSource.getLocation.toExternalForm
  play.api.Logger(getClass).info("DURABLE_RUNTIME_ORIGIN " + runtimeOrigin)

  private def viewJson(v: BatchProtocol.View): JsObject = Json.obj(
    "runtimeOrigin" -> runtimeOrigin,
    "jobId" -> v.jobId, "phase" -> v.phase, "inputClosed" -> v.inputClosed, "accepted" -> v.accepted,
    "succeeded" -> v.succeeded, "failed" -> v.failed, "held" -> v.held, "active" -> v.active,
    "activeBytes" -> v.activeBytes, "reason" -> v.reason, "truncated" -> v.truncated,
    "batches" -> JsArray(v.batches.map(b => Json.obj(
      "batchId" -> b.batchId, "area" -> b.area, "phase" -> b.phase, "itemIds" -> b.itemIds,
      "reservationId" -> b.reservationId, "deliveryId" -> b.deliveryId, "reason" -> b.reason))),
    "items" -> JsArray(v.items.map(i => Json.obj(
      "sequence" -> i.sequence, "itemId" -> i.itemId, "area" -> i.area, "phase" -> i.phase,
      "executions" -> JsObject(i.executions.toVector.sortBy(_._1).map { case (area, n) => area -> JsNumber(BigDecimal(n)) }),
      "reason" -> i.reason, "resultBase64" -> Base64.getEncoder.encodeToString(i.result.toArray),
      "resultUtf8" -> i.result.utf8String)))
  )

  private def rejected(reply: BatchProtocol.Reply, offers: Vector[JsObject] = Vector.empty): Result = reply match {
    case BatchProtocol.Rejected(reason) => Conflict(Json.obj("error" -> reason, "offers" -> JsArray(offers)))
    case BatchProtocol.Backpressured(reason) => TooManyRequests(Json.obj("error" -> reason,
      "offers" -> JsArray(offers), "retry" -> "Repeat the identical job/body; input was not closed by this request."))
      .withHeaders("Retry-After" -> "1")
    case _ => BadGateway(Json.obj("error" -> "Unexpected durable protocol reply; query committed state.",
      "offers" -> JsArray(offers)))
  }

  private def objectBody(body: String): Either[String, JsObject] = try {
    (if (body.trim.isEmpty) Json.obj() else Json.parse(body)) match {
      case obj: JsObject => Right(obj)
      case _ => Left("Expected a JSON object")
    }
  } catch { case NonFatal(_) => Left("Malformed JSON body") }

  private def startCount(body: String): Either[String, Int] = objectBody(body).flatMap { obj =>
    if (obj.keys.exists(_ != "count")) Left("Only the count field is supported")
    else obj.value.get("count") match {
      case None => Right(3)
      case Some(JsNumber(n)) if n.isValidInt && n.toInt >= 1 && n.toInt <= 20 => Right(n.toInt)
      case _ => Left("count must be an integer between 1 and 20")
    }
  }

  private def offerAll(job: ActorRef[BatchProtocol.Command], jobId: String, count: Int,
      payload: ByteString): Future[Result] = {
    def next(sequence: Int, offers: Vector[JsObject]): Future[Result] = {
      if (sequence == count) job.ask[BatchProtocol.Reply](reply => BatchProtocol.CloseInput(reply)).map {
        case BatchProtocol.Current(view) if view.inputClosed => Accepted(Json.obj(
          "offers" -> JsArray(offers), "state" -> viewJson(view)))
        case reply => rejected(reply, offers)
      }
      else {
        val itemId = s"$jobId-item-${sequence + 1}"
        job.ask[BatchProtocol.Reply](reply => BatchProtocol.Offer(sequence.toLong, itemId, payload,
          "recharge", "fund-1", reply)).flatMap {
          case BatchProtocol.Accepted(n, id, duplicate) if n == sequence.toLong && id == itemId =>
            next(sequence + 1, offers :+ Json.obj("sequence" -> n, "itemId" -> id, "duplicate" -> duplicate))
          case reply => Future.successful(rejected(reply, offers))
        }
      }
    }
    next(0, Vector.empty)
  }

  private def local(request: RequestHeader): Boolean =
    Set("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")(request.remoteAddress)

  def index: Action[AnyContent] = addCsrfToken(Action { implicit request =>
    if (!local(request)) Forbidden(Json.obj("error" -> "Local demo requests only"))
    else {
      val token = Json.stringify(JsString(CSRF.getToken(request).map(_.value).getOrElse(""))).replace("<", "\\u003c")
      Ok(page.replace("__CSRF_TOKEN_JSON__", token)).as("text/html; charset=utf-8").withHeaders("Cache-Control" -> "no-store")
    }
  })

  def start(jobId: String): Action[String] = Action.async(parse.tolerantText(4096)) { request =>
    if (!local(request)) Future.successful(Forbidden(Json.obj("error" -> "Local demo requests only")))
    else if (!BankWorkflowProgram.validId(jobId)) Future.successful(BadRequest(Json.obj("error" -> "Invalid jobId (safe ASCII, at most 80 characters)")))
    else startCount(request.body) match {
      case Left(reason) => Future.successful(BadRequest(Json.obj("error" -> reason)))
      case Right(count) if !BankWorkflowProgram.validId(s"$jobId-item-$count") =>
        Future.successful(BadRequest(Json.obj("error" -> "Leave room for -item-n: every derived itemId must also fit within 80 characters")))
      case Right(count) => guarded {
        // Identical bytes for every offer and every replay of this normalized body.
        // Including count makes a changed start body conflict, even after partial admission.
        val payload = ByteString(Json.stringify(Json.obj("schema" -> "bank-input-1", "jobId" -> jobId,
          "count" -> count, "amount" -> 100, "fund" -> "fund-1")))
        offerAll(workflowHost.job(jobId), jobId, count, payload)
      }
    }
  }

  def state(jobId: String): Action[AnyContent] = Action.async { request =>
    if (!local(request)) Future.successful(Forbidden(Json.obj("error" -> "Local demo requests only")))
    else if (!BankWorkflowProgram.validId(jobId)) Future.successful(BadRequest(Json.obj("error" -> "Invalid jobId")))
    else guarded {
      workflowHost.job(jobId).ask[BatchProtocol.Reply](reply => BatchProtocol.Get(reply, limit = 20)).map {
        case BatchProtocol.Current(view) => Ok(viewJson(view)).withHeaders("Cache-Control" -> "no-store")
        case reply => rejected(reply)
      }
    }
  }

  def reconcile(jobId: String, batchId: String): Action[Unit] = Action.async(parse.empty) { request =>
    if (!local(request)) Future.successful(Forbidden(Json.obj("error" -> "Local demo requests only")))
    else if (!BankWorkflowProgram.validId(jobId)) Future.successful(BadRequest(Json.obj("error" -> "Invalid jobId")))
    else guarded {
      workflowHost.reconcile(jobId, batchId).map {
        case BatchProtocol.Current(view) => Ok(viewJson(view))
        case reply => rejected(reply)
      }
    }
  }

  private def externalCall(command: String, fields: Vector[String] = Vector.empty): Future[Vector[String]] =
    Future(blocking(externalClient.call(command, fields)))(externalIo)
  private def controlsDisabled: Result = NotFound(Json.obj("error" -> "Local external controls are disabled in this configuration"))
  private def validOperationKey(key: String): Boolean =
    key.matches("[A-Za-z0-9][A-Za-z0-9_./:-]{0,511}") && key.endsWith("/execute")

  def externalTasks: Action[AnyContent] = Action.async { request =>
    if (!local(request)) Future.successful(Forbidden(Json.obj("error" -> "Local demo requests only")))
    else if (!controlsEnabled) Future.successful(controlsDisabled)
    else guarded {
      externalCall("TASKS").map {
        case response if response.headOption.contains("TASKS") &&
            response.tail.distinct.size == response.tail.size && response.tail.forall(validOperationKey) =>
          Ok(Json.obj("operationKeys" -> response.tail, "source" -> "independent external service"))
            .withHeaders("Cache-Control" -> "no-store")
        case response => BadGateway(Json.obj("error" -> "External task listing rejected", "externalResponse" -> response))
      }
    }
  }

  def externalComplete: Action[String] = Action.async(parse.tolerantText(4096)) { request =>
    if (!local(request)) Future.successful(Forbidden(Json.obj("error" -> "Local demo requests only")))
    else if (!controlsEnabled) Future.successful(controlsDisabled)
    else {
      val parsed = for {
        obj <- objectBody(request.body)
        _ <- Either.cond(obj.keys == Set("jobId", "operationKey"), (), "Expected only jobId and operationKey")
        jobId <- (obj \ "jobId").asOpt[String].filter(BankWorkflowProgram.validId).toRight("Invalid jobId")
        key <- (obj \ "operationKey").asOpt[String].filter(validOperationKey).toRight("Invalid operationKey")
      } yield jobId -> key
      parsed match {
        case Left(reason) => Future.successful(BadRequest(Json.obj("error" -> reason)))
        case Right((jobId, key)) => guarded {
          workflowHost.job(jobId).ask[BatchProtocol.Reply](reply => BatchProtocol.Get(reply, limit = 20)).flatMap {
            // Do not let this demo endpoint complete an arbitrary service task or another domain's job.
            case BatchProtocol.Current(view) if view.batches.exists(b => b.batchId + "/execute" == key) =>
              externalCall("COMPLETE", Vector(key)).map {
                case response @ Vector("SUCCEEDED", _, _, _) => Ok(Json.obj("externalResponse" -> response,
                  "note" -> "External receipt only. WorkflowHost must still validate and commit it; poll state."))
                case response @ Vector("NOT_FOUND", _) => Conflict(Json.obj("error" -> "External history is unknown; no resubmission authorized",
                  "externalResponse" -> response))
                case response @ Vector("REJECTED", _) => Conflict(Json.obj("externalResponse" -> response))
                case response => BadGateway(Json.obj("error" -> "Unrecognized external response", "externalResponse" -> response))
              }
            case _: BatchProtocol.Current => Future.successful(Forbidden(Json.obj("error" -> "Operation is not in this bank job's committed batch view")))
            case reply => Future.successful(rejected(reply))
          }
        }
      }
    }
  }

  private val page = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bank Durable Workflow</title><style>
body{font:15px system-ui,sans-serif;max-width:1120px;margin:32px auto;padding:0 18px;color:#183047;background:#f5f7fa}
section{background:white;padding:20px;margin:16px 0;border:1px solid #d5dfe8;border-radius:8px}
input,button{font:inherit;padding:7px 10px;margin:4px}button{cursor:pointer}button:disabled{cursor:wait}
table{border-collapse:collapse;width:100%;font-size:13px}td,th{border-bottom:1px solid #dde5ec;padding:8px;text-align:left;overflow-wrap:anywhere}
pre{white-space:pre-wrap;overflow-wrap:anywhere}small{color:#52677b}#tasks button{display:block;text-align:left;overflow-wrap:anywhere;max-width:100%}
</style></head><body><h1>银行持久化工作流</h1>
<p>充值（3 条或等待 300ms）→ 单笔购买。状态来自已提交的 Batch View，不由页面或观察者推断成功。</p>
<section><form id="start"><label>稳定 jobId <input id="job" value="bank-demo-1" maxlength="72" pattern="[A-Za-z0-9][A-Za-z0-9_-]*" required></label>
<label>条数 <input id="count" type="number" min="1" max="20" value="3" required></label><button id="startButton">提交 / 同输入恢复</button></form>
<small>同一 jobId 必须使用相同 count；部分接纳后可重放相同请求。不要用随机新 ID 掩盖未知结果。</small><pre id="message"></pre></section>
<section><h2>已提交状态</h2><p id="summary">正在读取</p><table><thead><tr><th>itemId</th><th>区域</th><th>阶段</th><th>执行序号</th><th>原因</th></tr></thead><tbody id="items"></tbody></table>
<details><summary>完整 Batch View JSON</summary><pre id="state"></pre></details><div id="reconcile"></div>
<small>自动查证有上限；人工按钮只查证原操作，不重置身份或强制成功。</small></section>
<section><h2>独立外部服务控制（可选）</h2><p>只在本机演示中启用。按钮请求外部服务实际完成任务；页面轮询不会调用 COMPLETE，也不会写入成功状态。</p>
<button id="loadTasks">列出当前作业的外部任务</button><div id="tasks"></div></section>
<script>
const csrfToken=__CSRF_TOKEN_JSON__;
const el=id=>document.getElementById(id); let currentView=null, reading=false;
const job=()=>el('job').value.trim();
const message=value=>{el('message').textContent=typeof value==='string'?value:JSON.stringify(value,null,2)};
async function request(url,options={}){
  const abort=new AbortController(), deadline=setTimeout(()=>abort.abort(),options.method==='POST'?60000:7000);
  try {
    const response=await fetch(url,{cache:'no-store',credentials:'same-origin',...options,signal:abort.signal,
      headers:{'Content-Type':'application/json','Csrf-Token':csrfToken}});
    const data=await response.json(); if(!response.ok)throw new Error(JSON.stringify(data,null,2)); return data;
  } finally {clearTimeout(deadline)}
}
async function refresh(){
  if(reading||!job())return; reading=true; const id=job();
  try{
    const view=await request('/api/bank-durable/'+encodeURIComponent(id)+'/state'); if(id!==job())return;
    currentView=view; el('state').textContent=JSON.stringify(view,null,2);
    el('reconcile').replaceChildren();
    for(const batch of view.batches){const button=document.createElement('button');
      button.textContent='查证原操作 '+batch.batchId;
      button.onclick=async()=>{button.disabled=true;try{
        message(await request('/api/bank-durable/'+encodeURIComponent(id)+'/reconcile?batchId='+encodeURIComponent(batch.batchId),{method:'POST',body:'{}'}));
      }catch(error){message(error.message)}finally{button.disabled=false}};
      el('reconcile').appendChild(button);
    }
    el('summary').textContent=`${view.phase} | accepted ${view.accepted} | succeeded ${view.succeeded} | failed ${view.failed} | held ${view.held} | active ${view.active} | inputClosed ${view.inputClosed}`;
    el('items').replaceChildren();
    for(const item of view.items){const row=document.createElement('tr');
      for(const value of [item.itemId,item.area,item.phase,JSON.stringify(item.executions),item.reason||'']){
        const cell=document.createElement('td');cell.textContent=value;row.appendChild(cell);
      } el('items').appendChild(row);
    }
  }finally{reading=false}
}
el('start').addEventListener('submit',async event=>{
  event.preventDefault();el('startButton').disabled=true;
  try{message(await request('/api/bank-durable/'+encodeURIComponent(job())+'/start',{
    method:'POST',body:JSON.stringify({count:Number(el('count').value)})}));await refresh();}
  catch(error){message(error.message)}finally{el('startButton').disabled=false}
});
async function loadTasks(){
  const id=job();await refresh();const response=await request('/api/bank-durable/external/tasks');
  if(id!==job())return;
  const owned=new Set(currentView&&currentView.jobId===id?currentView.batches.map(b=>b.batchId+'/execute'):[]);
  const keys=response.operationKeys.filter(key=>owned.has(key));el('tasks').replaceChildren();
  if(!keys.length)el('tasks').textContent='当前没有可见外部任务；可稍后再列出。';
  for(const key of keys){const button=document.createElement('button');button.textContent='由外部服务完成 '+key;
    button.onclick=async()=>{button.disabled=true;try{
      message(await request('/api/bank-durable/external/complete',{method:'POST',body:JSON.stringify({jobId:id,operationKey:key})}));
      await refresh();await loadTasks();
    }catch(error){message(error.message)}finally{button.disabled=false}};
    el('tasks').appendChild(button);
  }
}
el('loadTasks').onclick=()=>loadTasks().catch(error=>message(error.message));
el('job').onchange=()=>{currentView=null;el('tasks').replaceChildren();refresh().catch(error=>message(error.message))};
setInterval(()=>refresh().catch(error=>message(error.message)),1000);refresh().catch(error=>message(error.message));
</script></body></html>"""
}
