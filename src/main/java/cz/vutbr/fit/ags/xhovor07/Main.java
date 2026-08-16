/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 * 
 * Open Cybele 1
 * 
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.xhovor07;
import cybele.kernel.Cybele;

/**
 * 
 * @author Bedrich Hovorka
 *
 */
public class Main {

    /**
     * @param args
     */
    public static void main(String[] args) {
	Cybele.startUp();
	Cybele.createAgent(RailwayMainAgent.MAIN_AGENT_NAME, RailwayMainAgent.class.getName());
    }
}
