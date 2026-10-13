package wang.robby.console.android;

import android.test.InstrumentationTestCase;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import org.json.*;
import java.time.Instant;

/** Synthetic, offline contract tests. No API, credentials, device account or financial writes are used. */
public final class LedgerContractTest extends InstrumentationTestCase {
    private interface Checked { void run() throws Exception; }
    private static final String PROJECT="11111111111111111111111111111111";
    private static final String PERSON="22222222222222222222222222222222";
    private static final String REQUEST="33333333-3333-4333-8333-333333333333";
    private static String repeat(String value,int n){StringBuilder b=new StringBuilder();for(int i=0;i<n;i++)b.append(value);return b.toString();}
    private static JSONObject j(Object...pairs)throws Exception{return LedgerActions.json(pairs);}
    private static void rejects(Checked checked)throws Exception{try{checked.run();fail("Expected contract rejection");}catch(IllegalArgumentException expected){}}
    private static JSONObject person()throws Exception{return j("id",PERSON,"displayName","测试同行人","color","ocean","active",true);}
    private static JSONObject context()throws Exception{return j("schemaVersion",1,"actor",repeat("a",64),"generatedAt",Instant.now().toString(),"project",j("id",PROJECT,"name","合成测试账本"),"revision",7,
        "entry",null,"entryHash",null,"deletedAt",null,"people",new JSONArray().put(person()),
        "catalog",j("currencies",new JSONArray().put(j("code","CNY","label","人民币","digits",2)),"categories",new JSONArray().put("餐饮"),"paymentMethods",new JSONArray().put("卡"),"timeZones",new JSONArray().put(j("id","Asia/Shanghai","label","中国"))),
        "capabilities",j("create",true,"update",false,"delete",false,"restore",false),"privacy",j("classification","private-financial","cacheAllowed",false,"analyticsAllowed",false));}
    private static JSONObject value()throws Exception{return j("occurredAt","2026-01-01T13:00","occurredAtUtc","2026-01-01T00:00:00.000Z","paymentLocation","china","paymentTimeZone","Asia/Shanghai","amount","12.50","currency","CNY","category","餐饮","payerPersonId",PERSON,"participantPersonIds",new JSONArray().put(PERSON),"merchant","测试商户","paymentMethod","卡","note","");}
    private static JSONObject request()throws Exception{return j("schemaVersion",1,"project",PROJECT,"action","create","entryId",null,"expectedRevision",7,"expectedEntryHash",null,"requestId",REQUEST,"value",value());}
    private static JSONObject prepared(JSONObject request,JSONObject context)throws Exception{return j("schemaVersion",1,"actor",context.getString("actor"),"request",new JSONObject(request.toString()),"requestHash",LedgerActions.hash(request),"prepareToken","YWI."+repeat("b",43),"expiresAt",Instant.now().plusSeconds(240).toString(),"before",null,"people",context.getJSONArray("people"),"deletedAt",null);}

    public void testCanonicalJSONKeepsChineseAndDoesNotEscapeSlashes()throws Exception{
        assertEquals("{\"a\":\"中文/路径\",\"b\":1,\"c\":null}",LedgerActions.canonical(j("b",1,"c",null,"a","中文/路径")));
        assertEquals(LedgerActions.hash(j("a",1,"b",2)),LedgerActions.hash(j("b",2,"a",1)));
    }
    public void testCompatibleEntryPrepareAndDecimalNormalization()throws Exception{
        JSONObject c=context(),r=request();LedgerActions.validateContext("entry",c,PROJECT,null);
        JSONObject p=prepared(r,c);p.getJSONObject("request").getJSONObject("value").put("amount","12.5");p.put("requestHash",LedgerActions.hash(p.getJSONObject("request")));
        LedgerActions.validatePrepared("entry",p,r,c);
    }
    public void testChangedFinancialValueCannotPassPrepareEvenWithValidHash()throws Exception{
        JSONObject c=context(),r=request(),p=prepared(r,c);p.getJSONObject("request").getJSONObject("value").put("amount","99.00");p.put("requestHash",LedgerActions.hash(p.getJSONObject("request")));
        rejects(()->LedgerActions.validatePrepared("entry",p,r,c));
    }
    public void testChangedIdentityAndRequestBindingAreRejected()throws Exception{
        JSONObject c=context(),r=request(),p=prepared(r,c);p.put("actor",repeat("f",64));rejects(()->LedgerActions.validatePrepared("entry",p,r,c));
        JSONObject second=prepared(r,c);second.getJSONObject("request").put("expectedRevision",8);second.put("requestHash",LedgerActions.hash(second.getJSONObject("request")));rejects(()->LedgerActions.validatePrepared("entry",second,r,c));
    }
    public void testContextRejectsStaleVersionAndPrivacyChanges()throws Exception{
        JSONObject c=context();c.put("generatedAt",Instant.now().minusSeconds(60).toString());rejects(()->LedgerActions.validateContext("entry",c,PROJECT,null));
        JSONObject version=context();version.put("schemaVersion",2);rejects(()->LedgerActions.validateContext("entry",version,PROJECT,null));
        JSONObject privacy=context();privacy.getJSONObject("privacy").put("cacheAllowed",true);rejects(()->LedgerActions.validateContext("entry",privacy,PROJECT,null));
    }
    public void testExtraEnvelopeDataAndWrongReceiptIdentityAreRejected()throws Exception{
        JSONObject envelope=j("schemaVersion",1,"kind","context","value",context(),"extra","unexpected");rejects(()->LedgerActions.unwrap(envelope,"context"));
        JSONObject pending=j("schemaVersion",1,"kind","entry","actor",repeat("a",64),"project",PROJECT,"requestId",REQUEST,"requestHash",repeat("b",64),"prepareToken","YWI."+repeat("c",43),"expiresAt",Instant.now().plusSeconds(60).toString(),"action","create","targetId",null);
        LedgerActions.validatePending(pending);
        JSONObject receipt=j("schemaVersion",1,"state","not-found","project",PROJECT,"requestId","44444444-4444-4444-8444-444444444444");rejects(()->LedgerActions.validateReceipt("entry",receipt,pending));
    }
    public void testPeoplePreviewMustMatchExactRequestedAfterState()throws Exception{
        JSONObject person=person();person.put("sortOrder",0).put("createdAt",Instant.now().toString()).put("updatedAt",Instant.now().toString()).put("usedCount",0).put("deletedUseCount",0).put("legacyUseCount",0);
        JSONArray people=new JSONArray().put(person),colors=new JSONArray();for(String color:new String[]{"gold","ocean","fern","alpine","coral","violet","slate","sky"})colors.put(j("id",color,"label",color));
        JSONObject c=j("schemaVersion",1,"actor",repeat("a",64),"generatedAt",Instant.now().toString(),"project",j("id",PROJECT,"name","测试账本"),"revision",7,"peopleHash",LedgerActions.hash(people),"people",people,"colors",colors,"maximumPeople",128,"privacy",j("classification","private-financial","cacheAllowed",false,"analyticsAllowed",false));
        LedgerActions.validateContext("people",c,PROJECT,null);
        JSONObject r=j("schemaVersion",1,"project",PROJECT,"action","update","personId",PERSON,"expectedRevision",7,"expectedPeopleHash",c.getString("peopleHash"),"requestId",REQUEST,"value",j("displayName","新名字","color","ocean","active",false),"orderedIds",null);
        JSONArray after=new JSONArray().put(j("id",PERSON,"displayName","新名字","color","ocean","active",false,"sortOrder",0));
        JSONObject p=j("schemaVersion",1,"actor",c.getString("actor"),"request",r,"requestHash",LedgerActions.hash(r),"prepareToken","YWI."+repeat("b",43),"expiresAt",Instant.now().plusSeconds(240).toString(),"before",people,"after",after,"legacyNameMatches",0);
        LedgerActions.validatePrepared("people",p,r,c);after.getJSONObject(0).put("active",true);rejects(()->LedgerActions.validatePrepared("people",p,r,c));
    }
    public void testClipboardRendererDoesNotRevealInjectedContentOrObjects()throws Exception{
        JSONObject item=j("id",PERSON,"createdAt",Instant.now().toString(),"expiresAt",Instant.now().plusSeconds(60).toString(),"isPublic",false,"content","DO_NOT_RENDER_SYNTHETIC_SECRET");
        JSONObject response=j("apiVersion","2026-07-01","generatedAt",Instant.now().toString(),"partial",false,"stale",false,"sourceStatus",j("available",true,"stale",false),"items",new JSONArray().put(item));
        final StringBuilder text=new StringBuilder();getInstrumentation().runOnMainSync(()->{LinearLayout root=new LinearLayout(getInstrumentation().getTargetContext());FeatureRenderer.render(getInstrumentation().getTargetContext(),root,FeatureCatalog.find("clipboard"),response);collect(root,text);});
        assertFalse(text.toString().contains("DO_NOT_RENDER_SYNTHETIC_SECRET"));assertFalse(text.toString().contains("content"));assertTrue(text.toString().contains("私密"));
    }
    public void testRendererKeepsMissingCostsUnknownAndRejectsFutureSchema()throws Exception{
        JSONObject response=j("apiVersion","2026-08-01","schemaVersion",4,"generatedAt",Instant.now().toString(),"totals",j("costsUsd",null),"costStatus",j("total",j("available",false)),"models",new JSONArray());
        final StringBuilder text=new StringBuilder();getInstrumentation().runOnMainSync(()->{LinearLayout root=new LinearLayout(getInstrumentation().getTargetContext());FeatureRenderer.render(getInstrumentation().getTargetContext(),root,FeatureCatalog.find("openai"),response);collect(root,text);});
        assertTrue(text.toString().contains("暂不可用"));assertTrue(text.toString().contains("未知"));assertFalse(text.toString().contains("0 USD"));
        response.put("schemaVersion",5);assertFalse(FeatureRenderer.isCompatible(FeatureCatalog.find("openai"),response));
    }
    public void testReleasedMetadataSchemasAndPromptPrivacy()throws Exception{
        for(int schema:new int[]{1,2}){
            JSONObject prompt=j("schemaVersion",schema,"version","synthetic-version","model","synthetic-model","template","NEVER_RENDER_TEMPLATE");
            JSONObject catalog=j("schemaVersion",schema,"tools",new JSONArray());
            JSONObject publishing=j("schemaVersion",schema,"articles",new JSONArray());
            assertTrue(FeatureRenderer.isCompatible(FeatureCatalog.find("prompts"),prompt));
            assertTrue(FeatureRenderer.isCompatible(FeatureCatalog.find("miniprogram-catalog"),catalog));
            assertTrue(FeatureRenderer.isCompatible(FeatureCatalog.find("wechat-publishing"),publishing));
            final StringBuilder text=new StringBuilder();getInstrumentation().runOnMainSync(()->{LinearLayout root=new LinearLayout(getInstrumentation().getTargetContext());FeatureRenderer.render(getInstrumentation().getTargetContext(),root,FeatureCatalog.find("prompts"),prompt);collect(root,text);});
            assertFalse(text.toString().contains("NEVER_RENDER_TEMPLATE"));
            prompt.put("schemaVersion",3);catalog.put("schemaVersion",3);publishing.put("schemaVersion",3);
            assertFalse(FeatureRenderer.isCompatible(FeatureCatalog.find("prompts"),prompt));
            assertFalse(FeatureRenderer.isCompatible(FeatureCatalog.find("miniprogram-catalog"),catalog));
            assertFalse(FeatureRenderer.isCompatible(FeatureCatalog.find("wechat-publishing"),publishing));
        }
    }
    public void testEntryPickerLabelsFallBackToCategoryAndTolerateMissingFields()throws Exception{
        assertEquals("测试商户 · 12.50 CNY",FeatureRenderer.ledgerEntryLabel(j("merchant","测试商户","category","餐饮","amount","12.50","currency","CNY")));
        assertEquals("餐饮 · 8 JPY",FeatureRenderer.ledgerEntryLabel(j("merchant","  ","category","餐饮","amount","8","currency","JPY")));
        assertEquals("账目",FeatureRenderer.ledgerEntryLabel(new JSONObject()));
    }
    public void testUpstreamStatusShowsFeedAvailabilityNotProviderState()throws Exception{
        assertEquals("可用",FeatureRenderer.availability(j("available",true,"stale",false,"state","major_outage")));
        assertEquals("数据已陈旧",FeatureRenderer.availability(j("available",true,"stale",true,"state","operational")));
        assertEquals("暂不可用",FeatureRenderer.availability(j("available",false,"stale",false,"state","operational")));
    }
    private static void collect(View view,StringBuilder out){if(view instanceof TextView)out.append(((TextView)view).getText()).append('\n');if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),out);}}
}
