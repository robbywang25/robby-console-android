package wang.robby.console.android;

import java.util.*;

/** Process-memory reading context only. Never stores payloads, drafts, credentials or account identity. */
final class NavigationMemory {
    private static final Set<String> TABS=Set.of("总览","洞察","资产","工具","设置");
    private final Map<String,Destination> tabs=new HashMap<>();
    private final LinkedHashMap<String,Integer> offsets=new LinkedHashMap<>(64,.75f,true);
    static final class Destination {
        final String feature,project,cursor;
        final List<String> pages;
        Destination(String feature,String project,String cursor,Collection<String> pages){
            this.feature=feature;this.project=project;this.cursor=cursor;this.pages=List.copyOf(pages);
        }
        static Destination root(){return new Destination(null,null,null,List.of());}
    }
    Destination switchTo(String current,String target,Destination position){
        if(!TABS.contains(current)||!TABS.contains(target))throw new IllegalArgumentException("Unknown tab");
        if(current.equals(target))return position;
        tabs.put(current,position);return tabs.getOrDefault(target,Destination.root());
    }
    void rememberOffset(String route,int offset){
        if(route==null)return;offsets.put(route,Math.max(0,offset));
        while(offsets.size()>64)offsets.remove(offsets.keySet().iterator().next());
    }
    int offset(String route){return offsets.getOrDefault(route,0);}
    void clear(){tabs.clear();offsets.clear();}
}
