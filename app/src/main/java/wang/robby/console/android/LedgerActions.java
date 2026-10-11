package wang.robby.console.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.AtomicFile;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Native ledger mutation workflow. All writes require a reviewed prepare and a separate user confirmation. */
public final class LedgerActions {
    public interface Transport {
        JSONObject get(String relativePath) throws Exception;
        JSONObject post(String relativePath, JSONObject body) throws Exception;
    }
    private interface Job { JSONObject run() throws Exception; }
    private interface Result { void accept(JSONObject value) throws Exception; }
    private interface Tap { void run() throws Exception; }
    private static final String ENTRY = "entry", PEOPLE = "people";
    private static final Set<String> COLORS = Set.of("gold","ocean","fern","alpine","coral","violet","slate","sky");
    private final Activity activity;
    private final ConsoleTheme theme;
    private final Transport transport;
    private final Runnable onVerifiedWrite;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Journal journal;
    private AlertDialog dialog;
    private LinearLayout body;
    private volatile long generation;
    private volatile boolean closed;
    private boolean busy;
    private String kind, project, entryId;
    private JSONObject context, prepared, draftEntry;
    private final List<EditText> sensitiveInputs = new ArrayList<>();

    public LedgerActions(Activity activity, Transport transport, Runnable onVerifiedWrite) {
        this.activity = activity; this.theme = new ConsoleTheme(activity); this.transport = transport; this.onVerifiedWrite = onVerifiedWrite;
        journal = new Journal(activity.getApplicationContext());
    }
    /** Call on backgrounding, logout and destruction. Financial form content never enters saved state. */
    public void close() {
        closed = true; generation++; context = null; prepared = null; draftEntry = null; busy = false;
        for (EditText input : sensitiveInputs) input.setText(""); sensitiveInputs.clear();
        if (dialog != null) { dialog.dismiss(); dialog = null; } body = null;
    }
    /** Release the controller when its Activity is destroyed. Already-sent commits retain durable recovery proof. */
    public void dispose() { close(); worker.shutdown(); }
    public void showEntry(String projectId, String id) {
        if (!validProject(projectId) || (id != null && !id.matches("[a-f0-9]{32}"))) { failure("账本或账目标识无效。"); return; }
        start(ENTRY, projectId, id, id == null ? "记一笔" : "编辑账目");
        loadContext(false);
    }
    public void showPeople(String projectId) {
        if (!validProject(projectId)) { failure("账本标识无效。"); return; }
        start(PEOPLE, projectId, null, "人员管理"); loadContext(false);
    }
    public void showTrash(String projectId) {
        if (!validProject(projectId)) { failure("账本标识无效。"); return; }
        start(ENTRY, projectId, null, "最近删除"); loadTrash(0);
    }
    public void recover() {
        start(ENTRY, null, null, "核实提交结果");
        run(() -> { JSONObject p = journal.load(); check(p != null); return p; }, p -> pendingScreen(p));
    }
    private void start(String k, String p, String e, String title) {
        close(); closed = false; kind = k; project = p; entryId = e; openDialog(title);
    }
    private void openDialog(String title) {
        body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20),dp(8),dp(20),dp(16));
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        dialog = new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setNegativeButton("关闭", (d,w) -> close()).create();
        dialog.setOnCancelListener(d -> close()); dialog.show(); theme.dialog(dialog);
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void loadContext(boolean ignored) {
        final String k = kind, p = project, e = entryId;
        run(() -> {
            JSONObject pending = journal.load(); if (pending != null) return json("pending", pending);
            JSONObject c = unwrap(transport.get(contextPath(k,p,e)), "context"); validateContext(k,c,p,e); return c;
        }, c -> {
            if (c.has("pending")) { pendingScreen(c.getJSONObject("pending")); return; }
            context = c; prepared = null;
            if (PEOPLE.equals(k)) roster(); else entryEditor();
        });
    }
    private void entryEditor() throws Exception {
        clear(); title(context.getJSONObject("project").getString("name"));
        JSONObject old = nullableObject(context,"entry"); JSONObject caps = context.getJSONObject("capabilities");
        if (!context.isNull("deletedAt")) {
            entryReview(old, context.getJSONArray("people"));
            action("预览恢复", () -> preview(request(ENTRY,"restore",null,null)), caps.getBoolean("restore")); return;
        }
        JSONObject draft = draftEntry != null ? draftEntry : old;
        JSONObject cat = context.getJSONObject("catalog"); JSONArray currencies = cat.getJSONArray("currencies");
        List<String> codes = new ArrayList<>(); List<String> currencyLabels = new ArrayList<>();
        for (int i=0;i<currencies.length();i++) { JSONObject c = currencies.getJSONObject(i); codes.add(c.getString("code")); currencyLabels.add(c.getString("code")+" · "+c.getString("label")); }
        EditText amount = input("金额", draft == null ? "" : draft.getString("amount"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner currency = picker("币种",currencyLabels, codes.indexOf(draft == null ? "CNY" : draft.getString("currency")));
        List<String> categories = strings(cat.getJSONArray("categories")); Spinner category = picker("分类",categories, draft == null ? 0 : categories.indexOf(draft.getString("category")));
        EditText merchant = input("商户",draft == null ? "" : draft.getString("merchant"), InputType.TYPE_CLASS_TEXT);
        List<String> methods = strings(cat.getJSONArray("paymentMethods")); if (!methods.contains("")) methods.add(0, "");
        Spinner method = picker("付款方式",methods, draft == null ? 0 : methods.indexOf(draft.getString("paymentMethod")));
        List<JSONObject> people = availablePeople(context, old); List<String> personLabels = new ArrayList<>();
        personLabels.add("请选择付款人"); int selectedPayer = 0;
        for (int i=0;i<people.size();i++) { JSONObject p=people.get(i); personLabels.add(p.getString("displayName")+(p.getBoolean("active") ? "" : "（已停用）"));
            if (draft!=null && p.getString("id").equals(draft.optString("payerPersonId"))) selectedPayer=i+1; }
        Spinner payer = picker("付款人",personLabels,selectedPayer);
        title("参与分摊的人"); List<CheckBox> selected = new ArrayList<>(); Set<String> oldParticipants = draft==null ? Set.of() : new HashSet<>(strings(draft.getJSONArray("participantPersonIds")));
        for (JSONObject p : people) { CheckBox box=new CheckBox(activity);box.setMinHeight(dp(48));box.setText(p.getString("displayName"));box.setChecked(oldParticipants.contains(p.getString("id")));body.addView(box);selected.add(box); }
        String zone = draft == null ? ZoneId.systemDefault().getId() : draft.getString("paymentTimeZone");
        String wall = "";
        try { Instant instant=draft==null ? Instant.now() : Instant.parse(draft.getString("occurredAtUtc")); wall=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of(zone)).format(instant); } catch (RuntimeException ignored) { }
        EditText time=input("付款当地时间 YYYY-MM-DD HH:mm",wall,InputType.TYPE_CLASS_DATETIME);
        EditText timezone=input("付款时区",zone,InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText note=input("备注",draft==null ? "" : draft.getString("note"),InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        action("预览并确认", () -> {
            check(currency.getSelectedItemPosition()>=0 && category.getSelectedItemPosition()>=0 && payer.getSelectedItemPosition()>0);
            String code=codes.get(currency.getSelectedItemPosition()); int digits=currencies.getJSONObject(currency.getSelectedItemPosition()).getInt("digits");
            String amountValue=amount.getText().toString().trim(); check(amountValue.matches("[0-9]{1,16}(\\.[0-9]{1,3})?"));
            BigDecimal value = new BigDecimal(amountValue); check(value.signum()>0 && value.scale()<=digits);
            JSONArray participants = new JSONArray(); for (int i=0;i<selected.size();i++) if(selected.get(i).isChecked())participants.put(people.get(i).getString("id"));
            check(participants.length()>0 && participants.length()<=12);
            ZoneId z=ZoneId.of(timezone.getText().toString().trim());
            LocalDateTime local=LocalDateTime.parse(time.getText().toString().trim(), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT));
            List<ZoneOffset> offsets=z.getRules().getValidOffsets(local); check(offsets.size()==1); // Reject DST gaps or ambiguous wall times.
            Instant utc=local.toInstant(offsets.get(0)); String zoneId=z.getId();
            String location=zoneId.equals("Asia/Shanghai") ? "china" : zoneId.equals("Pacific/Auckland") ? "new-zealand" : Set.of("Australia/Melbourne","Australia/Sydney","Australia/Brisbane","Australia/Adelaide","Australia/Perth","Australia/Darwin","Australia/Hobart").contains(zoneId) ? "australia" : "other";
            JSONObject v=json("occurredAt",DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneId.of("Pacific/Auckland")).format(utc),
                "occurredAtUtc",DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(utc),
                "paymentLocation",location,"paymentTimeZone",zoneId,"amount",value.setScale(digits).toPlainString(),"currency",code,
                "category",categories.get(category.getSelectedItemPosition()),"payerPersonId",people.get(payer.getSelectedItemPosition()-1).getString("id"),
                "participantPersonIds",participants,"merchant",merchant.getText().toString().trim(),"paymentMethod",methods.get(method.getSelectedItemPosition()),"note",note.getText().toString().trim());
            validateValue(v, context); draftEntry=copy(v); preview(request(ENTRY,old==null ? "create" : "update",v,null));
        }, caps.getBoolean(old==null ? "create" : "update"));
        if (old!=null) action("预览删除这笔账目",()->preview(request(ENTRY,"delete",null,null)),caps.getBoolean("delete"));
        if (old!=null && !caps.getBoolean("update")) text("有有效退款的账目暂时不能编辑或删除。");
    }
    private void roster() throws Exception {
        clear(); title(context.getJSONObject("project").getString("name")); JSONArray people=context.getJSONArray("people");
        action("新增人员",()->personEditor(null),people.length()<128);
        action("调整顺序",()->ordering(new ArrayList<>(objects(people))),people.length()>1);
        for (JSONObject p : objects(people)) action(p.getString("displayName")+(p.getBoolean("active")?"":" · 已停用"),()->personEditor(p),true);
        if(people.length()==0)text("还没有人员");
    }
    private void personEditor(JSONObject person) throws Exception {
        clear(); title(person==null ? "新增人员" : "编辑人员");
        EditText name=input("姓名",person==null?"":person.getString("displayName"),InputType.TYPE_CLASS_TEXT);
        JSONArray colors=context.getJSONArray("colors"); List<String> labels=new ArrayList<>(),ids=new ArrayList<>();
        for(JSONObject c:objects(colors)){labels.add(c.getString("label"));ids.add(c.getString("id"));}
        Spinner color=picker("颜色",labels,person==null?0:ids.indexOf(person.getString("color")));
        Switch active=new Switch(activity);active.setText("启用");active.setMinHeight(dp(48));active.setChecked(person==null||person.getBoolean("active"));body.addView(active);
        text("停用后不能用于新账目，已有记录保留。");
        if(person!=null){text("引用账目："+person.getLong("usedCount")+" · 其中已删除："+person.getLong("deletedUseCount"));
            if(person.getLong("legacyUseCount")>0)text("旧账目仅按姓名关联。改名前需先在账目编辑中绑定人员。");}
        action("预览变更",()->{
            String n=name.getText().toString().trim();check(safeText(n,40,false));
            JSONObject v=json("displayName",n,"color",ids.get(color.getSelectedItemPosition()),"active",active.isChecked());
            preview(peopleRequest(person==null?"create":"update",person==null?null:person.getString("id"),v,null));
        },true);
        if(person!=null)action("预览删除未使用人员",()->preview(peopleRequest("delete",person.getString("id"),null,null)),person.getLong("usedCount")==0);
        action("返回名单",this::roster,true);
    }
    private void ordering(List<JSONObject> ordered) throws Exception {
        clear();title("人员顺序");
        for(int i=0;i<ordered.size();i++){final int index=i; JSONObject person=ordered.get(i); LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);
            TextView label=new TextView(activity);label.setText((i+1)+". "+person.getString("displayName"));label.setTextSize(16);label.setTextColor(theme.ink);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            Button up=new Button(activity);up.setText("上移");theme.button(up,false);up.setMinHeight(dp(48));up.setEnabled(i>0);up.setOnClickListener(v->tap(()->{Collections.swap(ordered,index,index-1);ordering(ordered);}));row.addView(up);body.addView(row);}
        action("预览排序",()->{JSONArray ids=new JSONArray();for(JSONObject p:ordered)ids.put(p.getString("id"));preview(peopleRequest("reorder",null,null,ids));},true);
        action("返回名单",this::roster,true);
    }
    private JSONObject request(String k,String action,JSONObject value,JSONArray ignored) throws Exception {
        return json("schemaVersion",1,"project",project,"action",action,"entryId",entryId,"expectedRevision",context.getLong("revision"),
            "expectedEntryHash",context.opt("entryHash"),"requestId",UUID.randomUUID().toString(),"value",value);
    }
    private JSONObject peopleRequest(String action,String person,JSONObject value,JSONArray order) throws Exception {
        String request=UUID.randomUUID().toString();
        return json("schemaVersion",1,"project",project,"action",action,"personId",action.equals("create")?request.replace("-",""):person,
            "expectedRevision",context.getLong("revision"),"expectedPeopleHash",context.getString("peopleHash"),"requestId",request,"value",value,"orderedIds",order);
    }
    private void preview(JSONObject request) throws Exception {
        final String k=kind; final JSONObject source=copy(context); final JSONObject original=copy(request);
        run(()->{
            check(journal.load()==null); check(canonical(original).getBytes(StandardCharsets.UTF_8).length<=16*1024);
            JSONObject p=unwrap(transport.post(base(k)+"prepare",original),"prepared");validatePrepared(k,p,original,source);return p;
        },p->{prepared=p;review();});
    }
    private void review() throws Exception {
        clear();JSONObject r=prepared.getJSONObject("request");String operation=r.getString("action");
        title(context.getJSONObject("project").getString("name"));text("操作："+actionName(operation));
        if(ENTRY.equals(kind)) {
            if(!prepared.isNull("before")){title("变更前");entryReview(prepared.getJSONObject("before"),prepared.getJSONArray("people"));}
            if(!r.isNull("value")){title("确认内容");entryReview(r.getJSONObject("value"),prepared.getJSONArray("people"));}
            if(operation.equals("delete"))text("删除后可从“最近删除”恢复。");
        } else {
            if(operation.equals("reorder")){int i=0;for(JSONObject p:objects(prepared.getJSONArray("after")))text((++i)+". "+p.getString("displayName"));}
            else {for(JSONObject p:objects(prepared.getJSONArray("before")))if(p.getString("id").equals(r.getString("personId"))){title("变更前");personReview(p);}
                for(JSONObject p:objects(prepared.getJSONArray("after")))if(p.getString("id").equals(r.getString("personId"))){title("确认内容");personReview(p);}}
            if(prepared.getLong("legacyNameMatches")>0)text("将按姓名关联 "+prepared.getLong("legacyNameMatches")+" 笔旧账目，请核对是否为同一人。");
        }
        action("确认"+actionName(operation),this::confirm,true);
        action("返回修改",()->{prepared=null;if(ENTRY.equals(kind))entryEditor();else roster();},true);
    }
    private void confirm() throws Exception {
        check(prepared!=null); final JSONObject p=copy(prepared); final String k=kind;
        check(Instant.parse(p.getString("expiresAt")).isAfter(Instant.now()));
        final long stamp=generation; final JSONObject pending=pending(k,p); prepared=null;
        run(()->{
            check(!closed&&generation==stamp);
            JSONObject identity=unwrap(transport.get(contextPath(k,pending.getString("project"),null)),"context");validateContext(k,identity,pending.getString("project"),null);
            check(identity.getString("actor").equals(pending.getString("actor")));
            check(!closed&&generation==stamp);journal.save(pending); // Durable proof precedes the only commit call.
            check(!closed&&generation==stamp);
            JSONObject receipt=unwrap(transport.post(base(k)+"commit",json("request",p.getJSONObject("request"),"prepareToken",p.getString("prepareToken"))),"receipt");
            return reconcile(k,receipt,pending);
        },result->completed(result));
    }
    private void pendingScreen(JSONObject pending) throws Exception {
        validatePending(pending); kind=pending.getString("kind"); project=pending.getString("project");
        clear();title("上次提交仍待核实");text("结果核实前，账目与人员都暂时不能再次提交。");
        action("查询提交结果",()->recoverPending(copy(pending)),true);
    }
    private void recoverPending(JSONObject pending) {
        run(()->{
            validatePending(pending);String k=pending.getString("kind"),p=pending.getString("project");
            JSONObject identity=unwrap(transport.get(contextPath(k,p,null)),"context");validateContext(k,identity,p,null);
            if(!identity.getString("actor").equals(pending.getString("actor")))throw new Rejected("wrong_account");
            JSONObject receipt=unwrap(transport.get(base(k)+"receipt?project="+p+"&request="+pending.getString("requestId")),"receipt");validateReceipt(k,receipt,pending);
            if(receipt.getString("state").equals("not-found")){
                JSONObject proof=json("project",p,"requestId",pending.getString("requestId"),"requestHash",pending.getString("requestHash"),"prepareToken",pending.getString("prepareToken"));
                receipt=unwrap(transport.post(base(k)+"resolve",proof),"receipt");validateReceipt(k,receipt,pending);
            }
            String state=receipt.getString("state");
            if(state.equals("recorded"))return reconcile(k,receipt,pending);
            if(state.equals("expired")||state.equals("superseded")){
                JSONObject fresh=unwrap(transport.get(contextPath(k,p,null)),"context");validateContext(k,fresh,p,null);
                check(fresh.getString("actor").equals(pending.getString("actor"))&&fresh.getLong("revision")>=identity.getLong("revision"));
                journal.clear(pending);return json("outcome",state);
            }
            return json("outcome","pending","pending",pending);
        },result->completed(result));
    }
    private JSONObject reconcile(String k,JSONObject receipt,JSONObject pending) throws Exception {
        validateReceipt(k,receipt,pending);check(receipt.getString("state").equals("recorded"));
        String id=ENTRY.equals(k)?receipt.getString("entryId"):null;
        JSONObject fresh=unwrap(transport.get(contextPath(k,pending.getString("project"),id)),"context");
        validateContext(k,fresh,pending.getString("project"),id);
        check(fresh.getString("actor").equals(pending.getString("actor"))&&fresh.getLong("revision")>=receipt.getLong("revision"));
        boolean exact=fresh.getString(ENTRY.equals(k)?"entryHash":"peopleHash").equals(receipt.getString("afterHash"));
        check(exact||fresh.getLong("revision")>receipt.getLong("revision"));journal.clear(pending);
        return json("outcome","recorded","exact",exact,"fresh",fresh);
    }
    private void completed(JSONObject result) throws Exception {
        clear();String outcome=result.getString("outcome");
        if(outcome.equals("pending")){pendingScreen(result.getJSONObject("pending"));text("暂未查到记录。请稍后继续查询；不要重复新增。");return;}
        title(outcome.equals("recorded")?"变更已核对":"已确认未写入");
        text(outcome.equals("recorded")?(result.getBoolean("exact")?"已保存，并重新读取核对。":"已提交；记录随后又有变化，请核对当前内容。"):
            outcome.equals("expired")?"上次请求已过期且未写入，可以重新预览。":"记录已经变化，上次请求未写入，请重新载入。");
        action("完成",()->{close();onVerifiedWrite.run();},true);
    }
    private void loadTrash(int offset) {
        final String p=project;
        run(()->{
            JSONObject pending=journal.load();if(pending!=null)return json("pending",pending);
            JSONObject value=unwrap(transport.get(base(ENTRY)+"trash?project="+p+"&offset="+offset),"trash");
            exact(value,"schemaVersion","project","total","offset","items","nextOffset");check(integer(value,"schemaVersion")==1&&value.getString("project").equals(p)&&integer(value,"offset")==offset);
            JSONArray items=value.getJSONArray("items");check(items.length()<=50&&integer(value,"total")>=offset+items.length());
            if(!value.isNull("nextOffset"))check(integer(value,"nextOffset")==offset+items.length()&&items.length()>0);
            for(JSONObject row:objects(items)){exact(row,"id","merchant","currency","amount","deletedAt");check(id(row.getString("id"))&&safeText(row.getString("merchant"),400,true)&&row.getString("currency").matches("[A-Z]{3}")&&money(row.getString("amount")));Instant.parse(row.getString("deletedAt"));}
            return value;
        },value->{if(value.has("pending")){pendingScreen(value.getJSONObject("pending"));return;}clear();title("最近删除");JSONArray items=value.getJSONArray("items");
            for(JSONObject row:objects(items))action(row.getString("merchant")+" · "+row.getString("currency")+" "+row.getString("amount"),()->showEntry(p,row.getString("id")),true);
            if(items.length()==0)text("没有已删除账目");if(!value.isNull("nextOffset"))action("下一页",()->loadTrash(value.getInt("nextOffset")),true);});
    }
    private void run(Job job,Result result) {
        if(busy||closed)return;busy=true;setEnabled(body,false);final long stamp=generation;
        worker.execute(()->{
            try{JSONObject value=job.run();activity.runOnUiThread(()->{if(!current(stamp))return;busy=false;setEnabled(body,true);try{result.accept(value);}catch(Exception e){showError(e);}});}
            catch(Exception e){activity.runOnUiThread(()->{if(!current(stamp))return;busy=false;setEnabled(body,true);showError(e);});}
        });
    }
    private boolean current(long stamp){return !closed&&generation==stamp&&!activity.isFinishing()&&!activity.isDestroyed();}
    private void showError(Exception error) {
        try{JSONObject pending=journal.load();if(pending!=null){pendingScreen(pending);text(error instanceof Rejected&&((Rejected)error).code.equals("wrong_account")?"请使用原来的登录身份核实此请求。":"提交结果尚未核实，请先查询，不要重复提交。");return;}}
        catch(Exception ignored){clear();title("提交凭证暂不可用");text("请解锁设备后重试。凭证未核实前不会发送新的账目。");return;}
        text(error instanceof Rejected ? errorMessage(((Rejected)error).code) : "操作未完成，请检查输入与网络后重试。");
        if(context==null)action("重新载入",()->loadContext(false),project!=null);
        else if(prepared==null)action("返回编辑",()->{if(ENTRY.equals(kind))entryEditor();else roster();},true);
    }
    private void failure(String message){new AlertDialog.Builder(activity).setMessage(message).setPositiveButton("知道了",null).show();}
    private void clear(){if(body!=null)body.removeAllViews();sensitiveInputs.clear();}
    private void title(String text){TextView v=new TextView(activity);v.setText(text);v.setTextSize(18);v.setTypeface(null,Typeface.BOLD);v.setTextColor(theme.ink);v.setPadding(0,dp(18),0,dp(10));v.setAccessibilityHeading(true);body.addView(v);}
    private void text(String text){if(body==null)return;TextView v=new TextView(activity);v.setText(text);v.setTextSize(15);v.setTextColor(theme.muted);v.setPadding(0,dp(7),0,dp(7));body.addView(v);}
    private EditText input(String label,String value,int type){text(label);EditText e=new EditText(activity);e.setText(value);e.setHint(label);e.setContentDescription(label);e.setInputType(type);e.setMinHeight(dp(48));e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);e.setSaveEnabled(false);theme.input(e);body.addView(e);sensitiveInputs.add(e);return e;}
    private Spinner picker(String label,List<String> values,int selected){text(label);Spinner s=new Spinner(activity);s.setContentDescription(label);s.setMinimumHeight(dp(48));s.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values));if(!values.isEmpty())s.setSelection(Math.max(0,selected));body.addView(s);return s;}
    private void action(String label,Tap action,boolean enabled){Button b=new Button(activity);b.setText(label);b.setAllCaps(false);theme.button(b,false);b.setMinHeight(dp(48));b.setEnabled(enabled);b.setTag(Boolean.valueOf(enabled));b.setOnClickListener(v->tap(action));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);body.addView(b,lp);}
    private void tap(Tap action){if(busy||closed)return;try{action.run();}catch(Exception e){showError(e);}}
    private void setEnabled(View v,boolean enabled){if(v==null)return;v.setEnabled(enabled&&(!Boolean.FALSE.equals(v.getTag())));if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++)setEnabled(g.getChildAt(i),enabled);}}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private void personReview(JSONObject p)throws Exception{text("姓名："+p.getString("displayName"));text("颜色："+p.getString("color"));text(p.getBoolean("active")?"启用":"停用");}
    private void entryReview(JSONObject v,JSONArray people)throws Exception{
        text("金额："+v.getString("currency")+" "+v.getString("amount"));text("商户："+v.getString("merchant"));text("分类："+v.getString("category"));text("付款方式："+v.getString("paymentMethod"));
        Map<String,String> names=new HashMap<>();for(JSONObject p:objects(people))names.put(p.getString("id"),p.getString("displayName"));
        text("付款人："+names.getOrDefault(v.optString("payerPersonId"),v.optString("payer","未绑定")));
        List<String> participants=new ArrayList<>();for(String id:strings(v.getJSONArray("participantPersonIds")))participants.add(names.getOrDefault(id,"未知人员"));text("参与人："+String.join("、",participants));
        text("UTC："+v.getString("occurredAtUtc"));text("付款时区："+v.getString("paymentTimeZone"));
        if(!v.getString("note").isEmpty())text("备注："+v.getString("note"));
    }
    private static String actionName(String action){switch(action){case"create":return"新增";case"update":return"修改";case"delete":return"删除";case"restore":return"恢复";case"reorder":return"排序";default:return"保存";}}
    private static String base(String kind){return "travel-ledger/"+(PEOPLE.equals(kind)?"people":"entry")+"-writes/";}
    private static String contextPath(String kind,String project,String entry){return base(kind)+"context?project="+project+(ENTRY.equals(kind)&&entry!=null?"&entry="+entry:"");}

    // Package-visible pure contract helpers are also exercised by local fixture tests.
    static JSONObject unwrap(JSONObject envelope,String expected)throws Exception{
        check(integer(envelope,"schemaVersion")==1);String kind=envelope.getString("kind");
        if(kind.equals("rejected")){exact(envelope,"schemaVersion","kind","code");throw new Rejected(envelope.getString("code"));}
        exact(envelope,"schemaVersion","kind","value");check(kind.equals(expected));return envelope.getJSONObject("value");
    }
    static void validateContext(String kind,JSONObject c,String project,String entry)throws Exception{
        if(ENTRY.equals(kind))exact(c,"schemaVersion","actor","generatedAt","project","revision","entry","entryHash","deletedAt","people","catalog","capabilities","privacy");
        else exact(c,"schemaVersion","actor","generatedAt","project","revision","peopleHash","people","colors","maximumPeople","privacy");
        check(integer(c,"schemaVersion")==1&&hashId(c.getString("actor"))&&integer(c,"revision")>0);
        exact(c.getJSONObject("project"),"id","name");check(c.getJSONObject("project").getString("id").equals(project)&&validProject(project)&&safeText(c.getJSONObject("project").getString("name"),160,false));
        long age=Duration.between(Instant.parse(c.getString("generatedAt")),Instant.now()).getSeconds();check(age>=-5&&age<=30);
        JSONObject privacy=c.getJSONObject("privacy");exact(privacy,"classification","cacheAllowed","analyticsAllowed");check(privacy.getString("classification").equals("private-financial")&&!bool(privacy,"cacheAllowed")&&!bool(privacy,"analyticsAllowed"));
        JSONArray people=c.getJSONArray("people");check(people.length()<=128);Set<String> ids=new HashSet<>();
        for(JSONObject p:objects(people)){
            if(ENTRY.equals(kind))exact(p,"id","displayName","color","active");
            else exact(p,"id","displayName","color","active","sortOrder","createdAt","updatedAt","usedCount","deletedUseCount","legacyUseCount");
            check(id(p.getString("id"))&&ids.add(p.getString("id"))&&safeText(p.getString("displayName"),PEOPLE.equals(kind)?40:160,false)&&COLORS.contains(p.getString("color")));bool(p,"active");
            if(PEOPLE.equals(kind)){check(integer(p,"sortOrder")>=0&&integer(p,"usedCount")>=0&&integer(p,"deletedUseCount")>=0&&integer(p,"deletedUseCount")<=integer(p,"usedCount")&&integer(p,"legacyUseCount")>=0&&integer(p,"legacyUseCount")<=integer(p,"usedCount"));Instant.parse(p.getString("createdAt"));Instant.parse(p.getString("updatedAt"));}
        }
        if(PEOPLE.equals(kind)){
            check(integer(c,"maximumPeople")==128&&c.getString("peopleHash").equals(hash(people)));JSONArray colors=c.getJSONArray("colors");List<String> expected=Arrays.asList("gold","ocean","fern","alpine","coral","violet","slate","sky");check(colors.length()==expected.size());
            for(int i=0;i<colors.length();i++){JSONObject color=colors.getJSONObject(i);exact(color,"id","label");check(color.getString("id").equals(expected.get(i))&&safeText(color.getString("label"),80,false));}return;
        }
        JSONObject row=nullableObject(c,"entry");check((entry==null)==(row==null));
        if(row==null){check(c.isNull("entryHash")&&c.isNull("deletedAt"));}
        else{validateEntry(row);check(row.getString("id").equals(entry)&&hashId(c.getString("entryHash")));if(!c.isNull("deletedAt"))Instant.parse(c.getString("deletedAt"));
            check(c.getString("entryHash").equals(hash(json("entry",row,"deletedAt",c.opt("deletedAt")))));}
        JSONObject caps=c.getJSONObject("capabilities");exact(caps,"create","update","delete","restore");for(String field:List.of("create","update","delete","restore"))bool(caps,field);
        JSONObject catalog=c.getJSONObject("catalog");exact(catalog,"currencies","categories","paymentMethods","timeZones");JSONArray currencies=catalog.getJSONArray("currencies");check(currencies.length()>0&&currencies.length()<=256);
        Set<String> codes=new HashSet<>();for(JSONObject currency:objects(currencies)){exact(currency,"code","label","digits");check(currency.getString("code").matches("[A-Z]{3}")&&codes.add(currency.getString("code"))&&safeText(currency.getString("label"),160,false)&&integer(currency,"digits")>=0&&integer(currency,"digits")<=3);}
        List<String> categories=strings(catalog.getJSONArray("categories"));check(!categories.isEmpty()&&categories.size()<=64);for(String s:categories)check(safeText(s,80,false));
        List<String> methods=strings(catalog.getJSONArray("paymentMethods"));check(methods.size()<=64);for(String s:methods)check(safeText(s,100,true));
    }
    private static void validateEntry(JSONObject e)throws Exception{
        exact(e,"id","occurredAt","occurredAtUtc","paymentLocation","paymentTimeZone","amount","currency","category","payer","participants","payerPersonId","participantPersonIds","storedCnyRate","storedCnyAmount","merchant","paymentMethod","note","refundedAmount","netAmount","refunds","createdAt","updatedAt");
        check(id(e.getString("id"))&&money(e.getString("amount"))&&money(e.getString("refundedAmount"))&&money(e.getString("netAmount"))&&e.getString("currency").matches("[A-Z]{3}"));
        check(safeText(e.getString("merchant"),400,true)&&safeText(e.getString("category"),100,false)&&safeText(e.getString("payer"),160,true)&&safeText(e.getString("note"),4000,true)&&safeText(e.getString("paymentMethod"),100,true));
        if(!e.isNull("payerPersonId"))check(id(e.getString("payerPersonId")));List<String> participants=strings(e.getJSONArray("participantPersonIds"));check(participants.size()<=12);for(String p:participants)check(id(p));
        check(e.getJSONArray("participants").length()<=12&&e.getJSONArray("refunds").length()<=10000);
    }
    static void validateValue(JSONObject v,JSONObject c)throws Exception{
        exact(v,"occurredAt","occurredAtUtc","paymentLocation","paymentTimeZone","amount","currency","category","payerPersonId","participantPersonIds","merchant","paymentMethod","note");
        check(safeText(v.getString("merchant"),400,true)&&safeText(v.getString("note"),4000,true)&&safeText(v.getString("paymentMethod"),100,true));
        JSONObject catalog=c.getJSONObject("catalog");check(strings(catalog.getJSONArray("categories")).contains(v.getString("category")));
        int digits=-1;for(JSONObject currency:objects(catalog.getJSONArray("currencies")))if(currency.getString("code").equals(v.getString("currency")))digits=(int)integer(currency,"digits");
        check(digits>=0&&money(v.getString("amount")));BigDecimal amount=new BigDecimal(v.getString("amount"));check(amount.signum()>0&&amount.scale()<=digits);
        Set<String> permitted=new HashSet<>();for(JSONObject person:availablePeople(c,nullableObject(c,"entry")))permitted.add(person.getString("id"));
        check(permitted.contains(v.getString("payerPersonId")));List<String> participants=strings(v.getJSONArray("participantPersonIds"));check(!participants.isEmpty()&&participants.size()<=12&&new HashSet<>(participants).size()==participants.size()&&permitted.containsAll(participants));
        Instant instant=Instant.parse(v.getString("occurredAtUtc"));check(instant.getEpochSecond()%60==0&&instant.getNano()==0);ZoneId.of(v.getString("paymentTimeZone"));
        check(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneId.of("Pacific/Auckland")).format(instant).equals(v.getString("occurredAt")));
        check(Set.of("china","new-zealand","australia","other").contains(v.getString("paymentLocation")));
    }
    static void validatePrepared(String kind,JSONObject p,JSONObject original,JSONObject context)throws Exception{
        if(ENTRY.equals(kind))exact(p,"schemaVersion","actor","request","requestHash","prepareToken","expiresAt","before","people","deletedAt");
        else exact(p,"schemaVersion","actor","request","requestHash","prepareToken","expiresAt","before","after","legacyNameMatches");
        check(integer(p,"schemaVersion")==1&&p.getString("actor").equals(context.getString("actor")));
        JSONObject r=p.getJSONObject("request");
        if(ENTRY.equals(kind))exact(r,"schemaVersion","project","action","entryId","expectedRevision","expectedEntryHash","requestId","value");
        else exact(r,"schemaVersion","project","action","personId","expectedRevision","expectedPeopleHash","requestId","value","orderedIds");
        for(String key:ENTRY.equals(kind)?List.of("schemaVersion","project","action","entryId","expectedRevision","expectedEntryHash","requestId"):List.of("schemaVersion","project","action","personId","expectedRevision","expectedPeopleHash","requestId","orderedIds"))check(canonical(r.opt(key)).equals(canonical(original.opt(key))));
        check(p.getString("requestHash").equals(hash(r))&&token(p.getString("prepareToken")));long remaining=Duration.between(Instant.now(),Instant.parse(p.getString("expiresAt"))).getSeconds();check(remaining>0&&remaining<=305);
        String action=r.getString("action");
        if(ENTRY.equals(kind)){
            check(Set.of("create","update","delete","restore").contains(action)&&context.getJSONObject("capabilities").getBoolean(action));
            check(canonical(p.opt("before")).equals(canonical(context.opt("entry")))&&canonical(p.getJSONArray("people")).equals(canonical(context.getJSONArray("people")))&&canonical(p.opt("deletedAt")).equals(canonical(context.opt("deletedAt"))));
            if(action.equals("create")||action.equals("update")){
                JSONObject value=r.getJSONObject("value");validateValue(value,context);JSONObject sent=original.getJSONObject("value");
                for(Iterator<String> it=sent.keys();it.hasNext();){String key=it.next();
                    if(key.equals("amount"))check(new BigDecimal(value.getString(key)).compareTo(new BigDecimal(sent.getString(key)))==0);
                    else if(Set.of("merchant","paymentMethod","note").contains(key))check(value.getString(key).equals(sent.getString(key).trim()));
                    else check(canonical(value.opt(key)).equals(canonical(sent.opt(key))));}
            }else check(r.isNull("value"));
        }else{
            check(Set.of("create","update","delete","reorder").contains(action)&&canonical(p.getJSONArray("before")).equals(canonical(context.getJSONArray("people")))&&integer(p,"legacyNameMatches")>=0);
            check(canonical(r.opt("value")).equals(canonical(original.opt("value"))));
            JSONArray expected=peopleAfter(context.getJSONArray("people"),r);check(canonical(expected).equals(canonical(p.getJSONArray("after"))));
        }
    }
    private static JSONArray peopleAfter(JSONArray before,JSONObject r)throws Exception{
        List<JSONObject> list=new ArrayList<>();for(JSONObject p:objects(before))list.add(json("id",p.getString("id"),"displayName",p.getString("displayName"),"color",p.getString("color"),"active",p.getBoolean("active"),"sortOrder",p.getLong("sortOrder")));
        String action=r.getString("action"),id=r.isNull("personId")?null:r.getString("personId");
        if(action.equals("create")||action.equals("update")){
            JSONObject value=r.getJSONObject("value");exact(value,"displayName","color","active");check(safeText(value.getString("displayName"),40,false)&&COLORS.contains(value.getString("color")));bool(value,"active");
            if(action.equals("create")){check(list.size()<128&&id(id));long last=-10;for(JSONObject p:list){check(!p.getString("id").equals(id));last=Math.max(last,p.getLong("sortOrder"));}
                list.add(json("id",id,"displayName",value.getString("displayName"),"color",value.getString("color"),"active",value.getBoolean("active"),"sortOrder",last+10));}
            else{boolean found=false;for(JSONObject p:list)if(p.getString("id").equals(id)){found=true;p.put("displayName",value.getString("displayName"));p.put("color",value.getString("color"));p.put("active",value.getBoolean("active"));}check(found);}
        }else if(action.equals("delete")){check(r.isNull("value")&&r.isNull("orderedIds"));boolean found=false;for(int i=list.size()-1;i>=0;i--)if(list.get(i).getString("id").equals(id)){list.remove(i);found=true;}check(found);}
        else{check(action.equals("reorder")&&r.isNull("value")&&r.isNull("personId"));List<String> order=strings(r.getJSONArray("orderedIds"));check(order.size()==list.size()&&new HashSet<>(order).size()==order.size());List<JSONObject> sorted=new ArrayList<>();for(int i=0;i<order.size();i++){JSONObject found=null;for(JSONObject p:list)if(p.getString("id").equals(order.get(i)))found=p;check(found!=null);found.put("sortOrder",i*10);sorted.add(found);}list=sorted;}
        JSONArray result=new JSONArray();for(JSONObject p:list)result.put(p);return result;
    }
    private static JSONObject pending(String kind,JSONObject prepared)throws Exception{
        JSONObject r=prepared.getJSONObject("request");String idKey=ENTRY.equals(kind)?"entryId":"personId";
        JSONObject value=json("schemaVersion",1,"kind",kind,"actor",prepared.getString("actor"),"project",r.getString("project"),"requestId",r.getString("requestId"),"requestHash",prepared.getString("requestHash"),"prepareToken",prepared.getString("prepareToken"),"expiresAt",prepared.getString("expiresAt"),"action",r.getString("action"),"targetId",r.opt(idKey));validatePending(value);return value;
    }
    static void validatePending(JSONObject p)throws Exception{
        exact(p,"schemaVersion","kind","actor","project","requestId","requestHash","prepareToken","expiresAt","action","targetId");String kind=p.getString("kind"),action=p.getString("action");
        check(integer(p,"schemaVersion")==1&&Set.of(ENTRY,PEOPLE).contains(kind)&&hashId(p.getString("actor"))&&validProject(p.getString("project"))&&uuid(p.getString("requestId"))&&hashId(p.getString("requestHash"))&&token(p.getString("prepareToken")));Instant.parse(p.getString("expiresAt"));
        if(ENTRY.equals(kind))check(Set.of("create","update","delete","restore").contains(action)&&(action.equals("create")?p.isNull("targetId"):id(p.getString("targetId"))));
        else check(Set.of("create","update","delete","reorder").contains(action)&&(action.equals("reorder")?p.isNull("targetId"):id(p.getString("targetId"))));
    }
    static void validateReceipt(String kind,JSONObject receipt,JSONObject pending)throws Exception{
        String state=receipt.getString("state");check(Set.of("recorded","not-found","expired","superseded").contains(state));
        if(!state.equals("recorded"))exact(receipt,"schemaVersion","state","project","requestId");
        else if(ENTRY.equals(kind))exact(receipt,"schemaVersion","state","project","requestId","requestHash","action","entryId","appliedAt","revision","afterHash","deleted");
        else exact(receipt,"schemaVersion","state","project","requestId","requestHash","action","personId","appliedAt","revision","afterHash");
        check(integer(receipt,"schemaVersion")==1&&receipt.getString("project").equals(pending.getString("project"))&&receipt.getString("requestId").equals(pending.getString("requestId")));
        if(!state.equals("recorded"))return;
        check(receipt.getString("requestHash").equals(pending.getString("requestHash"))&&receipt.getString("action").equals(pending.getString("action"))&&integer(receipt,"revision")>0&&hashId(receipt.getString("afterHash")));Instant.parse(receipt.getString("appliedAt"));
        if(ENTRY.equals(kind)){check(id(receipt.getString("entryId")));if(!pending.getString("action").equals("create"))check(receipt.getString("entryId").equals(pending.getString("targetId")));check(bool(receipt,"deleted")==pending.getString("action").equals("delete"));}
        else check(canonical(receipt.opt("personId")).equals(canonical(pending.opt("targetId"))));
    }
    private static List<JSONObject> availablePeople(JSONObject c,JSONObject old)throws Exception{
        Set<String> retained=new HashSet<>();if(old!=null){retained.add(old.optString("payerPersonId"));retained.addAll(strings(old.getJSONArray("participantPersonIds")));}
        List<JSONObject> out=new ArrayList<>();for(JSONObject p:objects(c.getJSONArray("people")))if(p.getBoolean("active")||retained.contains(p.getString("id")))out.add(p);return out;
    }
    static String canonical(Object value)throws Exception{
        if(value==null||value==JSONObject.NULL)return"null";
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;List<String> keys=new ArrayList<>();for(Iterator<String> it=o.keys();it.hasNext();)keys.add(it.next());Collections.sort(keys);List<String> fields=new ArrayList<>();for(String key:keys)fields.add(quote(key)+":"+canonical(o.get(key)));return"{"+String.join(",",fields)+"}";}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;List<String> values=new ArrayList<>();for(int i=0;i<a.length();i++)values.add(canonical(a.get(i)));return"["+String.join(",",values)+"]";}
        if(value instanceof String)return quote((String)value);
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Number){double n=((Number)value).doubleValue();check(Double.isFinite(n)&&Math.abs(n)<=9007199254740991d&&n==Math.rint(n));return Long.toString(((Number)value).longValue());}
        throw new IllegalArgumentException();
    }
    private static String quote(String text){StringBuilder out=new StringBuilder("\"");for(int i=0;i<text.length();i++){char c=text.charAt(i);switch(c){case'"':out.append("\\\"");break;case'\\':out.append("\\\\");break;case'\n':out.append("\\n");break;case'\r':out.append("\\r");break;case'\t':out.append("\\t");break;case'\b':out.append("\\b");break;case'\f':out.append("\\f");break;default:if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}}return out.append('"').toString();}
    static String hash(Object value)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static JSONObject json(Object...pairs)throws JSONException{JSONObject o=new JSONObject();for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]==null?JSONObject.NULL:pairs[i+1]);return o;}
    private static JSONObject copy(JSONObject o)throws JSONException{return new JSONObject(o.toString());}
    private static JSONObject nullableObject(JSONObject o,String key)throws Exception{check(o.has(key));return o.isNull(key)?null:o.getJSONObject(key);}
    static void exact(JSONObject o,String...fields)throws Exception{Set<String> actual=new HashSet<>();for(Iterator<String> it=o.keys();it.hasNext();)actual.add(it.next());check(actual.equals(new HashSet<>(Arrays.asList(fields))));}
    private static List<JSONObject> objects(JSONArray a)throws Exception{List<JSONObject> out=new ArrayList<>();for(int i=0;i<a.length();i++)out.add(a.getJSONObject(i));return out;}
    private static List<String> strings(JSONArray a)throws Exception{List<String> out=new ArrayList<>();for(int i=0;i<a.length();i++){check(a.get(i)instanceof String);out.add(a.getString(i));}return out;}
    private static long integer(JSONObject o,String key)throws Exception{Object n=o.get(key);check(n instanceof Number);double d=((Number)n).doubleValue();check(Double.isFinite(d)&&Math.abs(d)<=9007199254740991d&&d==Math.rint(d));return((Number)n).longValue();}
    private static boolean bool(JSONObject o,String key)throws Exception{Object b=o.get(key);check(b instanceof Boolean);return(Boolean)b;}
    private static boolean safeText(String s,int max,boolean empty){return(empty||!s.isEmpty())&&s.length()<=max&&s.codePoints().noneMatch(c->c<32&&c!=10&&c!=13||c==127);}
    private static boolean money(String s){return s.matches("-?(0|[1-9][0-9]{0,15})(\\.[0-9]{1,3})?");}
    private static boolean id(String s){return s!=null&&s.matches("[a-f0-9]{32}");}
    private static boolean validProject(String s){return id(s)||"new-zealand-south-island-2026".equals(s);}
    private static boolean hashId(String s){return s!=null&&s.matches("[a-f0-9]{64}");}
    private static boolean uuid(String s){return s.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}");}
    private static boolean token(String s){return s.length()<=2048&&s.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}");}
    private static void check(boolean condition)throws Exception{if(!condition)throw new IllegalArgumentException("Ledger contract validation failed");}
    private static final class Rejected extends Exception{final String code;Rejected(String code){this.code=code;}}
    private static String errorMessage(String code){switch(code){
        case"ledger_revision_conflict":case"entry_conflict":case"people_changed":return"账本已有变化，请关闭并重新载入后再预览。";
        case"prepare_expired":return"确认已过期，请重新预览。";
        case"entry_has_refunds":return"这笔账目有有效退款，暂时不能编辑或删除。";
        case"person_in_use":return"此人员仍被账目引用，可以停用；已删除账目的引用也需保留。";
        case"legacy_person_name_in_use":return"旧账目尚未绑定人员，请先在账目编辑中选择人员再改名。";
        case"duplicate_person_name":return"已有同名人员，请更换名称。";
        case"people_limit":return"人员已达上限。";
        case"wrong_account":return"请使用原登录身份核实待处理请求。";
        default:return"这次操作未通过校验，请检查输入并重新预览。";
    }}

    /** One device-wide encrypted recovery slot; contains proof metadata only, never amounts or names. */
    private static final class Journal {
        private static final Object LOCK=new Object();
        private static final String ALIAS="robby-console-ledger-recovery-v1";
        private final AtomicFile file;
        Journal(Context context){file=new AtomicFile(new File(context.getNoBackupFilesDir(),"ledger-recovery-v1.enc"));}
        JSONObject load()throws Exception{synchronized(LOCK){return read();}}
        void save(JSONObject value)throws Exception{synchronized(LOCK){validatePending(value);JSONObject existing=read();if(existing!=null){check(canonical(existing).equals(canonical(value)));return;}
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));byte[] encrypted=cipher.doFinal(canonical(value).getBytes(StandardCharsets.UTF_8));
            JSONObject envelope=json("version",1,"iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP),"data",Base64.encodeToString(encrypted,Base64.NO_WRAP));
            FileOutputStream stream=null;try{stream=file.startWrite();stream.write(envelope.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);}catch(Exception e){if(stream!=null)file.failWrite(stream);throw e;}
            check(canonical(read()).equals(canonical(value)));}}
        void clear(JSONObject expected)throws Exception{synchronized(LOCK){JSONObject value=read();check(value!=null&&canonical(value).equals(canonical(expected)));file.delete();check(!file.getBaseFile().exists());}}
        private JSONObject read()throws Exception{
            byte[] bytes;try(InputStream in=file.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] block=new byte[1024];int n;while((n=in.read(block))!=-1){check(out.size()+n<=16384);out.write(block,0,n);}bytes=out.toByteArray();}
            catch(FileNotFoundException e){if(!file.getBaseFile().exists())return null;throw e;}
            JSONObject envelope=new JSONObject(new String(bytes,StandardCharsets.UTF_8));exact(envelope,"version","iv","data");check(integer(envelope,"version")==1);
            byte[] iv=Base64.decode(envelope.getString("iv"),Base64.NO_WRAP);check(iv.length==12);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,iv));cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
            JSONObject value=new JSONObject(new String(cipher.doFinal(Base64.decode(envelope.getString("data"),Base64.NO_WRAP)),StandardCharsets.UTF_8));validatePending(value);return value;
        }
        private SecretKey key(boolean create)throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(ALIAS))return(SecretKey)store.getKey(ALIAS,null);check(create);
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());return generator.generateKey();}
    }
}
