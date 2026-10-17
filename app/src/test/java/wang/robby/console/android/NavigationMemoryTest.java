package wang.robby.console.android;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public final class NavigationMemoryTest {
 @Test public void switchingTabsRestoresEachIndependentDestination(){
  NavigationMemory m=new NavigationMemory();var ledger=new NavigationMemory.Destination("travel-ledger","synthetic-project","opaque-page",List.of("", "previous-page"));
  assertNull(m.switchTo("功能","设置",ledger).feature);
  var market=new NavigationMemory.Destination("market",null,null,List.of());
  assertSame(ledger,m.switchTo("设置","功能",market));
  assertSame(market,m.switchTo("功能","设置",ledger));
 }
 @Test public void selectedTabDoesNotResetReadingContext(){NavigationMemory m=new NavigationMemory();var d=new NavigationMemory.Destination("market",null,null,List.of());assertSame(d,m.switchTo("功能","功能",d));}
 @Test public void pageStackSnapshotCannotBeMutatedByLaterNavigation(){var pages=new ArrayDeque<String>();pages.add("");var d=new NavigationMemory.Destination("travel-ledger","demo","next",pages);pages.clear();assertEquals(List.of(""),d.pages);try{d.pages.add("new");fail();}catch(UnsupportedOperationException expected){}}
 @Test public void logoutDropsAllTabContextsAndOffsets(){NavigationMemory m=new NavigationMemory();m.switchTo("功能","总览",new NavigationMemory.Destination("travel-ledger","demo",null,List.of()));m.rememberOffset("tools/demo",900);m.clear();assertNull(m.switchTo("总览","功能",NavigationMemory.Destination.root()).feature);assertEquals(0,m.offset("tools/demo"));}
 @Test public void scrollingIsBoundedAndIsolatedByRoute(){NavigationMemory m=new NavigationMemory();m.rememberOffset("home",900);m.rememberOffset("market",-2);assertEquals(900,m.offset("home"));assertEquals(0,m.offset("market"));for(int i=0;i<65;i++)m.rememberOffset("page/"+i,i);assertEquals(0,m.offset("home"));assertEquals(64,m.offset("page/64"));}
}
