package com.jdkshen.aijspro.external.foreground;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/** A stable presentation of the same live execution tokens as the task manager. */
public final class RunningTaskState {
    public static final class Task {
        public final String token, name, engine;
        public final long startedAt;
        Task(String token, String name, String engine, long startedAt) {
            this.token=token; this.name=name; this.engine=engine; this.startedAt=startedAt;
        }
    }
    private final Map<String,Long> firstSeen=new HashMap<>();
    private List<Task> tasks=Collections.emptyList();

    public synchronized List<Task> update(JSONArray rows,long now) {
        List<Task> next=new ArrayList<>(); Set<String> seen=new LinkedHashSet<>();
        if(rows!=null) for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); if(row==null)continue;
            String token=row.optString("token","");
            if(token.isEmpty()||token.startsWith("timed:")||token.startsWith("intent:"))continue;
            String detail=row.optString("detail","");
            if("已结束".equals(detail)||"已停止".equals(detail)||!seen.add(token))continue;
            long start=row.optLong("startedAt",0);
            if(start<=0||start>now) start=firstSeen.containsKey(token)?firstSeen.get(token):now;
            firstSeen.put(token,start);
            String name=row.optString("name","");if(name.isEmpty())name="未命名脚本";
            next.add(new Task(token,name,row.optString("engine","JavaScript"),start));
        }
        firstSeen.keySet().retainAll(seen);
        Collections.sort(next,(a,b)->{int order=Long.compare(b.startedAt,a.startedAt);return order==0?a.token.compareTo(b.token):order;});
        tasks=Collections.unmodifiableList(next);return tasks;
    }
    public synchronized List<Task> tasks(){return tasks;}
    public synchronized boolean contains(String token){for(Task t:tasks)if(t.token.equals(token))return true;return false;}
    public synchronized long oldestStart(long fallback){long n=fallback;for(Task t:tasks)n=Math.min(n,t.startedAt);return n;}
    public synchronized String engines(){Set<String>s=new LinkedHashSet<>();for(Task t:tasks)s.add(t.engine);StringBuilder out=new StringBuilder();for(String label:s){if(out.length()>0)out.append(" / ");out.append(label);}return out.toString();}
    public synchronized String details(){StringBuilder s=new StringBuilder();for(int i=0;i<Math.min(tasks.size(),4);i++){Task t=tasks.get(i);if(i>0)s.append('\n');s.append(t.engine).append(" · ").append(t.name);}if(tasks.size()>4)s.append("\n另有 ").append(tasks.size()-4).append(" 个脚本");return s.toString();}
    public static String elapsed(long ms){long seconds=Math.max(0,ms)/1000;return seconds>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);}
}
