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

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.HeadlessException;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Map.Entry;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolBar;

import cybele.kernel.Cybele;

/**
 * specifiction of GUI
 * @author Bedrich Hovorka
 *
 */
public class Gui extends JFrame {
    private static final long serialVersionUID = 1L;
    private final RailwayCanvas railwayCanvas;

    /**
     * @param mainAgent
     * @throws HeadlessException bezhlava vyjimka :o)
     */
    public Gui(RailwayMainAgent mainAgent) throws HeadlessException {
	super("AGS OpenCybele Demo by xhovor07 @ FIT VUT Brno");
	setDefaultCloseOperation(EXIT_ON_CLOSE);
	setLayout(new BorderLayout());
	railwayCanvas = new RailwayCanvas(mainAgent);
	
	final JComponent panel = createBar();
	getContentPane().add(panel, BorderLayout.NORTH);
	
	final JScrollPane scrollPane = new JScrollPane(railwayCanvas);
	scrollPane.setSize(800, 400);
	getContentPane().add(scrollPane, BorderLayout.CENTER);
	
	final JScrollPane scrollPane2 = new JScrollPane(new JTable(mainAgent.getTrainTableModel()));
	scrollPane2.setPreferredSize(new Dimension(800, 180));
	getContentPane().add(scrollPane2, BorderLayout.SOUTH);
	
	addComponentListener(new ComponentAdapter() {
		@Override
		public void componentResized(ComponentEvent arg0) {
			//zmeni-li se velikost okna je treba nektere komponety obnovit
			railwayCanvas.revalidate();//mizeni a navraceni scrollbaru...
		}
	
	});
	// This was commented out in 2008, so closing the window took EXIT_ON_CLOSE's
	// System.exit and left the kernel to be torn down by the JVM. It now goes through
	// RunControl, which flushes the trace, prints the stop banner and terminates the
	// kernel; the exit status stays 0, as EXIT_ON_CLOSE's always was.
	addWindowListener(new WindowAdapter() {
	    @Override
	    public void windowClosing(WindowEvent e) {
		RunControl.stop(RunControl.EXIT_OK, "GUI window closed");
	    }
	
	});
	setSize(800, 650);
    }

    @SuppressWarnings("boxing")
    private JComponent createBar() {
	final JToolBar bar = new JToolBar();
	bar.setFloatable(false);
	bar.setPreferredSize(new Dimension(800, 30));
//	bar.add(new PaceChangeAction("Very Fast", 40));
	// sim.gui.paces, default "Fast=8,Normal=1,Slow=0.3" - the original three buttons
	for (Entry<String, Double> pace : ScenarioConfig.get().getGuiPaces().entrySet()) {
	    bar.add(new PaceChangeAction(pace.getKey(), pace.getValue()));
	}
	return bar;
    }
    
    private final class PaceChangeAction extends AbstractAction {
	private static final long serialVersionUID = 1L;
	private final double pace;

	private PaceChangeAction(String name, double pace) {
	    super(name);
	    this.pace = pace;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
	    Cybele.setPace(RailwayMainAgent.CLOCK_ID, pace);
	}
    }
}
