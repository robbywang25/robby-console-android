package wang.robby.console.billinglab;
import java.util.*;
import wang.robby.console.billinglab.ScenarioEngine.*;

/** Pure controller for manual simulation. Keeps paid boundaries and cache reconciliation testable. */
public final class ScenarioSession {
 public static final long DAY=86400000L;
 public enum Outcome { APPLIED, PAYMENT_REQUIRED, HOLD_REQUIRES_PAYMENT, IGNORED }
 private final ScenarioEngine store=new ScenarioEngine(),view=new ScenarioEngine();
 private final Map<String,Long> paidThrough=new HashMap<>();
 private long now=DAY,revision;
 public long now(){return now;}
 public Tier tier(String owner){return view.tier(owner,now);}
 public void advanceDays(int days){if(days<1||days>365)throw new IllegalArgumentException();now+=days*DAY;}
 public void clearView(){view.clear();}
 public void restore(){view.restore(store.snapshot());}
 public void reset(){store.clear();view.clear();paidThrough.clear();now=DAY;revision=0;}
 public List<ScenarioEngine.Record> records(){return store.snapshot();}
 public Outcome event(String owner,Product product,State state){
  String id="sim-"+owner.toLowerCase(Locale.ROOT)+"-"+product.name().toLowerCase(Locale.ROOT);
  ScenarioEngine.Record previous=store.snapshot().stream().filter(r->r.id.equals(id)).findFirst().orElse(null);
  boolean recurring=state==State.CANCELED||state==State.GRACE||state==State.HOLD;
  if(recurring&&(previous==null||!Set.of(State.ACTIVE,State.CANCELED,State.GRACE,State.HOLD).contains(previous.state)))return Outcome.PAYMENT_REQUIRED;
  if(previous!=null&&previous.state==State.HOLD&&(state==State.CANCELED||state==State.GRACE))return Outcome.HOLD_REQUIRES_PAYMENT;
  long until=product==Product.LIFETIME?0:now+30*DAY;
  if(recurring){
   until=previous.validUntil;
   if(state==State.GRACE)until=paidThrough.getOrDefault(id,previous.validUntil)+3*DAY;
  }
  var record=new ScenarioEngine.Record(id,owner,product,state,++revision,until);
  if(!store.apply(record))return Outcome.IGNORED;
  if(state==State.ACTIVE&&product!=Product.LIFETIME)paidThrough.put(id,until);
  view.restore(store.snapshot());return Outcome.APPLIED;
 }
}
