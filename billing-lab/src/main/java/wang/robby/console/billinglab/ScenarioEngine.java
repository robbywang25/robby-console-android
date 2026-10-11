package wang.robby.console.billinglab;
import java.util.*;

/** LOCAL SIMULATOR ONLY. Not receipt verification and never a production entitlement authority. */
public final class ScenarioEngine {
 public enum Product { LIFETIME, PLUS, PRO }
 public enum State { PENDING, UNVERIFIED, ACTIVE, CANCELED, GRACE, HOLD, EXPIRED, REFUNDED, REVOKED }
 public enum Tier { FREE, BASIC, PLUS, PRO }
 public static final class Record {
  public final String id,owner; public final Product product; public final State state;public final long revision,validUntil;
  public Record(String id,String owner,Product product,State state,long revision,long validUntil){
   if(id==null||!id.matches("sim-[a-z0-9-]{1,80}")||(owner==null||!Set.of("A","B").contains(owner))||product==null||state==null||revision<1||validUntil<0)throw new IllegalArgumentException("Invalid simulated record");
   if(product==Product.LIFETIME && (state==State.CANCELED||state==State.GRACE||state==State.HOLD))throw new IllegalArgumentException("Recurring state on lifetime purchase");
   this.id=id;this.owner=owner;this.product=product;this.state=state;this.revision=revision;this.validUntil=validUntil;
  }
 }
 private final Map<String,Record> records=new HashMap<>();
 public boolean apply(Record next){
  Record old=records.get(next.id);
  if(old!=null){
   if(!old.owner.equals(next.owner)||old.product!=next.product)throw new IllegalArgumentException("Receipt binding changed");
   if(next.revision<=old.revision)return false;
   if(old.state!=State.PENDING&&old.state!=State.UNVERIFIED&&(next.state==State.PENDING||next.state==State.UNVERIFIED))return false;
   if(old.state==State.HOLD&&(next.state==State.CANCELED||next.state==State.GRACE))return false;
   if((old.state==State.REFUNDED||old.state==State.REVOKED)&&next.state!=old.state)return false;
  }
  if(old==null&&records.size()>=64)throw new IllegalStateException("Simulation capacity reached");
  records.put(next.id,next);return true;
 }
 public Tier tier(String owner,long now){
  if((owner==null||!Set.of("A","B").contains(owner))||now<0)throw new IllegalArgumentException();
  Tier tier=Tier.FREE;
  for(Record r:records.values()){
   if(!owner.equals(r.owner))continue;
   boolean grants=r.state==State.ACTIVE||r.state==State.CANCELED||r.state==State.GRACE;
   if(!grants||(r.product!=Product.LIFETIME&&now>=r.validUntil))continue;
   Tier current=r.product==Product.LIFETIME?Tier.BASIC:r.product==Product.PLUS?Tier.PLUS:Tier.PRO;
   if(current.ordinal()>tier.ordinal())tier=current;
  }return tier;
 }
 public List<Record> snapshot(){return List.copyOf(records.values());}
 public void restore(List<Record> verifiedSimulationSnapshot){
  ScenarioEngine next=new ScenarioEngine();for(Record r:verifiedSimulationSnapshot)next.apply(r);
  records.clear();records.putAll(next.records);
 }
 public void clear(){records.clear();}
}
