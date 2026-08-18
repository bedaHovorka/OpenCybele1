import java.util.*;
import cz.vutbr.fit.ags.xhovor07.util.*;

/** Experiment D: capture today's actual iteration order at the four
 *  HashMap/HashSet sites that decide BEHAVIOUR (not just display). */
public class Order {
    public static void main(String[] a) {
        UnorientedGraph<String,String> net = new HashMapGraph<String,String>();
        net.put("stA", "stH", "tr1");
        net.put("stH", "stG", "tr2");
        net.put("stG", "stE", "tr3");
        net.put("stE", "stD", "tr4");
        net.put("stD", "stB", "tr5");
        net.put("stF", "stE", "tr6");
        net.put("stC", "stF", "tr7");

        System.out.println("SITE-1 RailwayMainAgent.java:116  net.nodeSet()   (Station creation order)");
        System.out.println("   " + net.nodeSet());
        System.out.println("SITE-2 RailwayMainAgent.java:123  net.values()    (RoadAgent creation order)");
        System.out.println("   " + net.values());
        System.out.println("SITE-3 RailwayMainAgent.java:125  net.allNodesWithEdge(road)  -> [leftStation, rightStation]");
        List<String> roads = new ArrayList<String>(net.values());
        for (String r : roads) {
            Collection<String> c = net.allNodesWithEdge(r);
            Object[] arr = c.toArray();
            System.out.println("   " + r + " -> left=" + arr[0] + " right=" + arr[1] + "   (raw " + c + ")");
        }
        System.out.println("SITE-4 Util.java:106,123  privatePath nodesToEdges HashMap  -> chosen ROUTE");
        String[][] od = {{"stA","stB"},{"stA","stC"},{"stB","stA"},{"stB","stC"},{"stC","stB"},{"stC","stA"}};
        for (String[] p : od) {
            System.out.println("   path " + p[0] + " -> " + p[1] + " : " + Util.path(net, p[0], p[1]));
        }
        System.out.println("SITE-4b Util.pathDirection (per-station PATH_FIND answers)");
        for (String from : new String[]{"stA","stB","stC","stD","stE","stF","stG","stH"}) {
            StringBuilder sb = new StringBuilder("   from " + from + " :");
            for (String to : new String[]{"stA","stB","stC"}) {
                if (from.equals(to)) continue;
                sb.append(" ->").append(to).append("=").append(Util.pathDirection(net, from, to));
            }
            System.out.println(sb);
        }
        System.out.println("Doubleton hashCodes (String.hashCode is spec'd, never randomised):");
        System.out.println("   Doubleton(stA,stH).hashCode()=" + new Doubleton<String>("stA","stH").hashCode()
                + "  Doubleton(stH,stA).hashCode()=" + new Doubleton<String>("stH","stA").hashCode());
    }
}
