import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

/**
 * Newton's Cradle — Coefficient of Restitution Simulation
 * Blue ball (p1): left pivot, at rest = coordinate origin (0,0)
 * Red  ball (p2): right pivot, raised to the right at chosen angle
 * Mass is set directly; radius is derived from density.
 */
public class NewtonsCradle extends JFrame {

    // ── Materials ─────────────────────────────────────────────────────────────
    enum Material {
        PLASTIC   ("Plastic",    0.70, 1200,
                new Color(0xD32F2F), new Color(0x1565C0),
                new Color(0xFF8A80), new Color(0x82B1FF)),
        PLASTICINE("Plasticine", 0.10, 1700,
                new Color(0xAD1457), new Color(0x2E7D32),
                new Color(0xF48FB1), new Color(0xA5D6A7)),
        METAL     ("Metal",      0.90, 7800,
                new Color(0xB71C1C), new Color(0x0D47A1),
                new Color(0xFFCDD2), new Color(0xBBDEFB));
        final String name; final double e, density;
        final Color base1, base2, hi1, hi2;
        Material(String n, double e, double d,
                 Color b1, Color b2, Color h1, Color h2) {
            name=n; this.e=e; density=d; base1=b1; base2=b2; hi1=h1; hi2=h2;
        }
        /** Compute radius (m) from mass (kg) and density */
        double radius(double mass) {
            return Math.cbrt(mass / (density * (4.0/3.0) * Math.PI));
        }
    }

    // ── Physics constants ─────────────────────────────────────────────────────
    static final double PIVOT_Y  = 0.06;
    static final double STRING_L = 0.30;
    static final double GRAVITY  = 9.81;
    static final double DT       = 0.003;
    static final int    SUBSTEPS = 5;
    static final double DAMPING  = 0.003;
    static final double MASS_MIN = 0.010; // kg
    static final double MASS_MAX = 1.000; // kg
    static final double MASS_DEF = 0.100; // kg default

    // ── Pendulum ──────────────────────────────────────────────────────────────
    static class Pendulum {
        double pivotX, angle, omega, radius, mass;
        Color  baseColor, hiColor;
        Material material;
        boolean isRed;
        double originX, originY; // blue ball rest = coordinate origin

        final List<Double> tH  = new ArrayList<>(), vH  = new ArrayList<>(),
                xH  = new ArrayList<>(), rY  = new ArrayList<>(),
                keH = new ArrayList<>(), peH = new ArrayList<>();
        double prevKE = 0;

        double ballX()  { return pivotX + Math.sin(angle) * STRING_L; }
        double ballY()  { return PIVOT_Y + Math.cos(angle) * STRING_L; }
        double relX()   { return ballX() - originX; }
        double relH()   { return originY - ballY(); }   // height above origin, +up
        double speed()  { return Math.abs(omega) * STRING_L; }
        double ke()     { return 0.5 * mass * speed() * speed(); }
        double pe()     { return mass * GRAVITY * Math.max(0, relH()); }

        void record(double t) {
            tH.add(t); vH.add(speed());
            xH.add(relX()); rY.add(relH());
            keH.add(ke()); peH.add(pe());
        }
        void trim(int max) {
            if (tH.size() > max) {
                int c = tH.size() - max;
                tH.subList(0,c).clear(); vH.subList(0,c).clear();
                xH.subList(0,c).clear(); rY.subList(0,c).clear();
                keH.subList(0,c).clear(); peH.subList(0,c).clear();
            }
        }
    }

    // ── App state ─────────────────────────────────────────────────────────────
    CradleCanvas canvas;
    GraphPanel   velGraph, xGraph, yGraph, keGraph, peGraph;
    JLabel       eLabel, radiusLabel1, radiusLabel2;
    JComboBox<String> mat1Box, mat2Box, angleBox;
    JSpinner     mass1Spin, mass2Spin;
    JButton      startBtn, resetBtn;

    Pendulum p1, p2;
    javax.swing.Timer timer;
    double  simTime  = 0;
    boolean running  = false;
    double  computedE = 0;
    double  pivot1X, pivot2X;

    // ─────────────────────────────────────────────────────────────────────────
    public NewtonsCradle() {
        super("Newton's Cradle — Coefficient of Restitution");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        getContentPane().setBackground(Color.WHITE);
        setLayout(new BorderLayout(4,4));
        buildUI();
        pack();
        setMinimumSize(new Dimension(1050, 740));
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ── Build UI ──────────────────────────────────────────────────────────────
    void buildUI() {
        // ── Control strip ────────────────────────────────────────────────────
        JPanel ctrl = new JPanel(new GridBagLayout());
        ctrl.setBackground(new Color(0xF0F0F0));
        ctrl.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0,0,1,0,new Color(0xCCCCCC)),
                BorderFactory.createEmptyBorder(7,10,7,10)));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2,4,2,4);
        gc.anchor = GridBagConstraints.WEST;

        String[] mats   = {"Plastic","Plasticine","Metal"};
        String[] angles = {"High (60°)","Medium (35°)"};

        mat1Box  = combo(mats);
        mat2Box  = combo(mats); mat2Box.setSelectedIndex(2); // default Metal for red
        angleBox = combo(angles);

        // Mass spinners: 10g–1000g, step 10g, shown in grams for readability
        SpinnerNumberModel sm1 = new SpinnerNumberModel(100, 10, 1000, 10);
        SpinnerNumberModel sm2 = new SpinnerNumberModel(100, 10, 1000, 10);
        mass1Spin = new JSpinner(sm1);
        mass2Spin = new JSpinner(sm2);
        mass1Spin.setPreferredSize(new Dimension(72,22));
        mass2Spin.setPreferredSize(new Dimension(72,22));
        ((JSpinner.DefaultEditor)mass1Spin.getEditor()).getTextField()
                .setFont(new Font("Arial",Font.PLAIN,12));
        ((JSpinner.DefaultEditor)mass2Spin.getEditor()).getTextField()
                .setFont(new Font("Arial",Font.PLAIN,12));

        radiusLabel1 = new JLabel();
        radiusLabel2 = new JLabel();
        radiusLabel1.setFont(new Font("Arial",Font.ITALIC,11));
        radiusLabel2.setFont(new Font("Arial",Font.ITALIC,11));
        radiusLabel1.setForeground(new Color(0x555555));
        radiusLabel2.setForeground(new Color(0x555555));

        // Live update listeners
        ActionListener matUpdate = e -> { refreshAppearance(); canvas.repaint(); };
        mat1Box.addActionListener(matUpdate);
        mat2Box.addActionListener(matUpdate);
        angleBox.addActionListener(matUpdate);
        ChangeListener massUpdate = e -> { refreshAppearance(); canvas.repaint(); };
        mass1Spin.addChangeListener(massUpdate);
        mass2Spin.addChangeListener(massUpdate);

        // Row 0: Blue ball
        gc.gridy=0;
        gc.gridx=0; ctrl.add(lbl("Blue ball (at rest):"),gc);
        gc.gridx=1; ctrl.add(mat1Box,gc);
        gc.gridx=2; ctrl.add(lbl("Mass (g):"),gc);
        gc.gridx=3; ctrl.add(mass1Spin,gc);
        gc.gridx=4; ctrl.add(radiusLabel1,gc);

        // Row 1: Red ball
        gc.gridy=1;
        gc.gridx=0; ctrl.add(lbl("Red ball (released):"),gc);
        gc.gridx=1; ctrl.add(mat2Box,gc);
        gc.gridx=2; ctrl.add(lbl("Mass (g):"),gc);
        gc.gridx=3; ctrl.add(mass2Spin,gc);
        gc.gridx=4; ctrl.add(radiusLabel2,gc);
        gc.gridx=5; ctrl.add(lbl("Angle:"),gc);
        gc.gridx=6; ctrl.add(angleBox,gc);

        // Row 2: e label + buttons
        eLabel = new JLabel("e = —");
        eLabel.setFont(new Font("Arial",Font.BOLD,12));
        eLabel.setForeground(new Color(0x222222));
        gc.gridy=2; gc.gridx=0; gc.gridwidth=5; ctrl.add(eLabel,gc);
        gc.gridwidth=1;

        startBtn = btn("▶  Play",  new Color(0x1565C0), Color.WHITE);
        resetBtn = btn("↺  Reset", new Color(0xB71C1C), Color.WHITE);
        gc.gridx=5; gc.gridy=2; ctrl.add(startBtn,gc);
        gc.gridx=6;             ctrl.add(resetBtn,gc);

        startBtn.addActionListener(e -> toggleSim());
        resetBtn.addActionListener(e -> resetSim());
        add(ctrl, BorderLayout.NORTH);

        // ── Canvas ────────────────────────────────────────────────────────────
        canvas = new CradleCanvas();
        canvas.setPreferredSize(new Dimension(1050, 260));
        add(canvas, BorderLayout.CENTER);

        // ── Graph row: Speed | X | Y | KE | PE ───────────────────────────────
        JPanel gRow = new JPanel(new GridLayout(1,5,4,0));
        gRow.setBackground(Color.WHITE);
        gRow.setBorder(BorderFactory.createEmptyBorder(3,6,6,6));
        velGraph = new GraphPanel("Speed (m/s)");
        xGraph   = new GraphPanel("Position X (m)");
        yGraph   = new GraphPanel("Height above rest (m)");
        keGraph  = new GraphPanel("Kinetic Energy (J)");
        peGraph  = new GraphPanel("Potential Energy (J)");
        gRow.add(velGraph); gRow.add(xGraph); gRow.add(yGraph);
        gRow.add(keGraph);  gRow.add(peGraph);
        gRow.setPreferredSize(new Dimension(1050, 210));
        add(gRow, BorderLayout.SOUTH);

        resetSim();
    }

    // ── Radius label helper ───────────────────────────────────────────────────
    void updateRadiusLabel(JLabel lbl, Material mat, double massKg) {
        double r = mat.radius(massKg) * 100.0; // cm
        lbl.setText(String.format("r = %.1f cm", r));
    }

    // ── Refresh appearance only (no physics reset) ────────────────────────────
    void refreshAppearance() {
        if (p1==null || p2==null) return;
        Material m1 = Material.values()[mat1Box.getSelectedIndex()];
        Material m2 = Material.values()[mat2Box.getSelectedIndex()];
        double mass1 = ((Number)mass1Spin.getValue()).intValue() / 1000.0;
        double mass2 = ((Number)mass2Spin.getValue()).intValue() / 1000.0;

        p1.material=m1; p1.baseColor=m1.base2; p1.hiColor=m1.hi2;
        p1.mass=mass1;  p1.radius=m1.radius(mass1);

        p2.material=m2; p2.baseColor=m2.base1; p2.hiColor=m2.hi1;
        p2.mass=mass2;  p2.radius=m2.radius(mass2);

        computedE = Math.sqrt(m1.e * m2.e);
        updateELabel(m1, m2, mass1, mass2);
        updateRadiusLabel(radiusLabel1, m1, mass1);
        updateRadiusLabel(radiusLabel2, m2, mass2);

        if (!running) {
            double gap = p1.radius + p2.radius;
            pivot1X = 0.50 - gap/2.0; pivot2X = 0.50 + gap/2.0;
            p1.pivotX = pivot1X; p2.pivotX = pivot2X;
            double deg = (angleBox.getSelectedIndex()==0) ? 60.0 : 35.0;
            p2.angle = Math.toRadians(deg);
            p1.angle = 0;
        }
    }

    // ── Full reset ────────────────────────────────────────────────────────────
    void resetSim() {
        if (timer!=null) timer.stop();
        running=false; startBtn.setText("▶  Play"); simTime=0;

        Material m1 = Material.values()[mat1Box.getSelectedIndex()];
        Material m2 = Material.values()[mat2Box.getSelectedIndex()];
        double mass1 = ((Number)mass1Spin.getValue()).intValue() / 1000.0;
        double mass2 = ((Number)mass2Spin.getValue()).intValue() / 1000.0;
        double deg = (angleBox.getSelectedIndex()==0) ? 60.0 : 35.0;
        double ang = Math.toRadians(deg);

        computedE = Math.sqrt(m1.e * m2.e);
        updateELabel(m1, m2, mass1, mass2);
        updateRadiusLabel(radiusLabel1, m1, mass1);
        updateRadiusLabel(radiusLabel2, m2, mass2);

        double r1 = m1.radius(mass1), r2 = m2.radius(mass2);
        pivot1X = 0.50 - (r1+r2)/2.0;
        pivot2X = 0.50 + (r1+r2)/2.0;
        double blueRestY = PIVOT_Y + STRING_L;
        double blueRestX = pivot1X;

        p1 = new Pendulum(); p1.isRed=false;
        p1.pivotX=pivot1X; p1.angle=0; p1.omega=0;
        p1.mass=mass1; p1.radius=r1; p1.material=m1;
        p1.baseColor=m1.base2; p1.hiColor=m1.hi2;
        p1.originX=blueRestX; p1.originY=blueRestY;
        p1.prevKE=0;

        p2 = new Pendulum(); p2.isRed=true;
        p2.pivotX=pivot2X; p2.angle=ang; p2.omega=0;
        p2.mass=mass2; p2.radius=r2; p2.material=m2;
        p2.baseColor=m2.base1; p2.hiColor=m2.hi1;
        p2.originX=blueRestX; p2.originY=blueRestY;
        p2.prevKE=p2.ke();

        p1.record(0); p2.record(0);
        for (GraphPanel gp : new GraphPanel[]{velGraph,xGraph,yGraph,keGraph,peGraph})
            gp.clear();
        canvas.repaint();
    }

    void updateELabel(Material m1, Material m2, double mass1, double mass2) {
        eLabel.setText(String.format(
                "Coefficient of Restitution e = %.3f  [Blue: %s %.0fg  |  Red: %s %.0fg]",
                computedE, m1.name, mass1*1000, m2.name, mass2*1000));
    }

    void toggleSim() {
        if (running) { timer.stop(); running=false; startBtn.setText("▶  Play"); }
        else {
            running=true; startBtn.setText("⏸  Pause");
            timer = new javax.swing.Timer(16, e -> step());
            timer.start();
        }
    }

    // ── Physics ───────────────────────────────────────────────────────────────
    void step() {
        for (int s=0; s<SUBSTEPS; s++) { integrate(p1); integrate(p2); resolveCollision(); }
        simTime += DT * SUBSTEPS;
        p1.record(simTime); p2.record(simTime);
        p1.trim(800); p2.trim(800);
        // s1=RED(p2), s2=BLUE(p1) so C_RED draws p2, C_BLUE draws p1
        velGraph.update(p2.tH, p2.vH,  p1.tH, p1.vH);
        xGraph  .update(p2.tH, p2.xH,  p1.tH, p1.xH);
        yGraph  .update(p2.tH, p2.rY,  p1.tH, p1.rY);
        keGraph .update(p2.tH, p2.keH, p1.tH, p1.keH);
        peGraph .update(p2.tH, p2.peH, p1.tH, p1.peH);
        canvas.repaint();
    }

    void integrate(Pendulum p) {
        double alpha = -(GRAVITY/STRING_L)*Math.sin(p.angle) - DAMPING*p.omega;
        p.omega += alpha*DT; p.angle += p.omega*DT;
    }

    void resolveCollision() {
        double x1=p1.ballX(), y1=p1.ballY(), x2=p2.ballX(), y2=p2.ballY();
        double dist = Math.hypot(x2-x1, y2-y1);
        double minD = p1.radius + p2.radius;
        if (dist < minD && dist > 1e-9) {
            double nx=(x2-x1)/dist, ny=(y2-y1)/dist;
            double v1x=p1.omega*STRING_L*Math.cos(p1.angle), v1y=-p1.omega*STRING_L*Math.sin(p1.angle);
            double v2x=p2.omega*STRING_L*Math.cos(p2.angle), v2y=-p2.omega*STRING_L*Math.sin(p2.angle);
            double relV = (v1x-v2x)*nx + (v1y-v2y)*ny;
            if (relV<=0) return;
            double j = -(1+computedE)*relV / (1.0/p1.mass + 1.0/p2.mass);
            double dv1x=-j*nx/p1.mass, dv1y=-j*ny/p1.mass;
            double dv2x= j*nx/p2.mass, dv2y= j*ny/p2.mass;
            p1.omega -= (dv1x*Math.cos(p1.angle) - dv1y*Math.sin(p1.angle)) / STRING_L;
            p2.omega -= (dv2x*Math.cos(p2.angle) - dv2y*Math.sin(p2.angle)) / STRING_L;
            double push = (minD-dist)*0.5/STRING_L;
            p1.angle -= Math.copySign(Math.asin(Math.min(0.99,push)),1);
            p2.angle += Math.copySign(Math.asin(Math.min(0.99,push)),1);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    JLabel lbl(String t) {
        JLabel l=new JLabel(t); l.setForeground(new Color(0x333333));
        l.setFont(new Font("Arial",Font.PLAIN,12)); return l;
    }
    JComboBox<String> combo(String[] items) {
        JComboBox<String> c=new JComboBox<>(items);
        c.setFont(new Font("Arial",Font.PLAIN,12)); c.setBackground(Color.WHITE); return c;
    }
    JButton btn(String txt, Color bg, Color fg) {
        JButton b=new JButton(txt); b.setBackground(bg); b.setForeground(fg);
        b.setFont(new Font("Arial",Font.BOLD,12)); b.setFocusPainted(false);
        b.setOpaque(true); b.setBorderPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(5,14,5,14)); return b;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Cradle Canvas
    // ════════════════════════════════════════════════════════════════════════
    class CradleCanvas extends JPanel {
        CradleCanvas() { setBackground(Color.WHITE); }
        double scX, oX, scY, oY;
        void computeScale() {
            int W=getWidth(), H=getHeight();
            scX=W*0.50; oX=W*0.25; scY=H*1.5; oY=-H*0.02;
        }
        int wx(double v) { return (int)(v*scX+oX); }
        int wy(double v) { return (int)(v*scY+oY); }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (p1==null) return;
            Graphics2D g = (Graphics2D)g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            computeScale();
            int W=getWidth(), H=getHeight();

            int fy=wy(PIVOT_Y);
            int px1=wx(pivot1X), px2=wx(pivot2X);
            int maxR=(int)(Math.max(p1.radius,p2.radius)*scX);
            int barExt=maxR*4, poleH=H*2/5;

            // Frame
            g.setColor(new Color(0x424242));
            g.setStroke(new BasicStroke(5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.drawLine(px1-barExt,fy,px2+barExt,fy);
            g.setStroke(new BasicStroke(3f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.drawLine(px1-barExt,fy,px1-barExt,fy+poleH);
            g.drawLine(px2+barExt,fy,px2+barExt,fy+poleH);
            g.drawLine(px1-barExt,fy+poleH,px2+barExt,fy+poleH);

            // Origin crosshair at blue ball rest pos
            int ox=wx(pivot1X), oy=wy(PIVOT_Y+STRING_L);
            g.setColor(new Color(0x1565C0));
            g.setStroke(new BasicStroke(1f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_BEVEL,
                    0,new float[]{3,3},0));
            g.drawLine(ox-16,oy,ox+16,oy);
            g.drawLine(ox,oy-16,ox,oy+16);
            g.setFont(new Font("Arial",Font.PLAIN,9));
            g.drawString("(0,0)",ox+5,oy+10);

            // Angle arc for red ball
            if (!running && p2.angle!=0) {
                g.setColor(new Color(200,60,60,130));
                g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_BEVEL,
                        0,new float[]{5,4},0));
                int arcR=(int)(STRING_L*scY*0.38);
                int acx=wx(pivot2X), acy=wy(PIVOT_Y);
                g.drawArc(acx-arcR,acy-arcR,arcR*2,arcR*2,90,(int)(-Math.toDegrees(p2.angle)));
                g.setFont(new Font("Arial",Font.BOLD,12));
                g.setColor(new Color(0xB71C1C));
                g.drawString(String.format("θ=%.0f°",Math.abs(Math.toDegrees(p2.angle))),
                        acx+arcR/2+4, acy-5);
            }

            // Strings
            g.setColor(new Color(0x888888));
            g.setStroke(new BasicStroke(1.4f));
            g.drawLine(wx(p1.pivotX),wy(PIVOT_Y),wx(p1.ballX()),wy(p1.ballY()));
            g.drawLine(wx(p2.pivotX),wy(PIVOT_Y),wx(p2.ballX()),wy(p2.ballY()));

            paintBall(g,p1); paintBall(g,p2);
            paintLegend(g,W);
        }

        void paintBall(Graphics2D g, Pendulum p) {
            int bx=wx(p.ballX()), by=wy(p.ballY());
            int r=Math.max(5,(int)(p.radius*scX));
            g.setColor(new Color(0,0,0,20));
            g.fillOval(bx-r+2,by-r+3,r*2,r*2);
            Material mat=p.material;
            if (mat==Material.METAL) {
                GradientPaint gp=new GradientPaint(bx-r,by-r,p.hiColor,bx+r,by+r,p.baseColor);
                g.setPaint(gp); g.fillOval(bx-r,by-r,r*2,r*2);
                g.setColor(new Color(255,255,255,100)); g.fillOval(bx-r/3,by-r,r*2/3,r);
                g.setColor(new Color(0,0,0,18)); g.setStroke(new BasicStroke(0.5f));
                for (int i=-r;i<r;i+=5){int xc=bx+i;if(xc>bx-r&&xc<bx+r)g.drawLine(xc,by-r,xc,by+r);}
            } else if (mat==Material.PLASTICINE) {
                g.setColor(p.baseColor); g.fillOval(bx-r,by-r,r*2,r*2);
                g.setColor(p.baseColor.darker());
                int sp=Math.max(r/3,3);
                for(int i=-1;i<=1;i++)for(int j=-1;j<=1;j++)
                    if((i+j)%2==0)g.fillOval(bx+i*sp-2,by+j*sp-2,4,4);
            } else {
                RadialGradientPaint rg=new RadialGradientPaint(bx-r/3f,by-r/3f,r*1.2f,
                        new float[]{0f,0.4f,1f},new Color[]{p.hiColor,p.baseColor,p.baseColor.darker()});
                g.setPaint(rg); g.fillOval(bx-r,by-r,r*2,r*2);
            }
            g.setPaint(null);
            g.setColor(p.baseColor.darker().darker());
            g.setStroke(new BasicStroke(1.5f)); g.drawOval(bx-r,by-r,r*2,r*2);
        }

        void paintLegend(Graphics2D g, int W) {
            int x=W-210, y=8;
            g.setFont(new Font("Arial",Font.BOLD,11));
            g.setColor(p1.baseColor); g.fillOval(x,y,13,13);
            g.setColor(new Color(0x222222));
            g.drawString(String.format("Blue  %s  %.0fg  r=%.1fcm",
                    p1.material.name, p1.mass*1000, p1.radius*100), x+17, y+11);
            y+=18;
            g.setColor(p2.baseColor); g.fillOval(x,y,13,13);
            g.setColor(new Color(0x222222));
            g.drawString(String.format("Red   %s  %.0fg  r=%.1fcm",
                    p2.material.name, p2.mass*1000, p2.radius*100), x+17, y+11);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Graph Panel  — hover crosshair shows (t, value) for each series
    // ════════════════════════════════════════════════════════════════════════
    class GraphPanel extends JPanel {
        final String title;
        List<Double> t1, s1, t2, s2;   // s1=RED(p2), s2=BLUE(p1)
        int showBalls = 0;              // 0=both 1=red 2=blue

        static final Color C_RED  = new Color(0xD32F2F);
        static final Color C_BLUE = new Color(0x1565C0);

        JPanel header;
        JToggleButton btnBoth, btnRed, btnBlue;
        JButton expandBtn;
        int mouseScreenX = -1;

        GraphPanel(String title) {
            this.title = title;
            setLayout(new BorderLayout(0,0));
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(new Color(0xCCCCCC)));
            buildHeader();
        }

        void buildHeader() {
            header = new JPanel();
            header.setLayout(new BoxLayout(header,BoxLayout.Y_AXIS));
            header.setBackground(new Color(0xEEEEEE));
            header.setBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(0xCCCCCC)));

            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT,3,2));
            row1.setBackground(new Color(0xEEEEEE));
            JLabel tl=new JLabel(title);
            tl.setFont(new Font("Arial",Font.BOLD,10)); tl.setForeground(new Color(0x111111));
            row1.add(tl);
            expandBtn=new JButton("⤢"); expandBtn.setFont(new Font("Arial",Font.PLAIN,10));
            expandBtn.setMargin(new Insets(1,4,1,4)); expandBtn.setFocusPainted(false);
            expandBtn.setToolTipText("Expand");
            expandBtn.addActionListener(e->openExpanded());
            row1.add(expandBtn);
            header.add(row1);

            JPanel row2=new JPanel(new FlowLayout(FlowLayout.LEFT,3,1));
            row2.setBackground(new Color(0xE0E0E0));
            btnBoth=tog("Both",true); btnRed=tog("Red",false); btnBlue=tog("Blue",false);
            ButtonGroup bg=new ButtonGroup(); bg.add(btnBoth); bg.add(btnRed); bg.add(btnBlue);
            JLabel sl=new JLabel("Show:"); sl.setFont(new Font("Arial",Font.PLAIN,9));
            row2.add(sl); row2.add(btnBoth); row2.add(btnRed); row2.add(btnBlue);
            btnBoth.addActionListener(e->{showBalls=0;repaint();});
            btnRed .addActionListener(e->{showBalls=1;repaint();});
            btnBlue.addActionListener(e->{showBalls=2;repaint();});
            header.add(row2);
            add(header,BorderLayout.NORTH);

            addMouseMotionListener(new MouseMotionAdapter(){
                public void mouseMoved(MouseEvent e){mouseScreenX=e.getX();repaint();}
            });
            addMouseListener(new MouseAdapter(){
                public void mouseExited(MouseEvent e){mouseScreenX=-1;repaint();}
            });
        }

        JToggleButton tog(String txt,boolean sel){
            JToggleButton b=new JToggleButton(txt,sel);
            b.setFont(new Font("Arial",Font.PLAIN,9));
            b.setMargin(new Insets(1,4,1,4)); b.setFocusPainted(false); return b;
        }

        void clear(){t1=s1=t2=s2=null;repaint();}

        void update(List<Double> t1,List<Double> s1,List<Double> t2,List<Double> s2){
            this.t1=new ArrayList<>(t1); this.s1=new ArrayList<>(s1);
            this.t2=new ArrayList<>(t2); this.s2=new ArrayList<>(s2);
            repaint();
        }

        void openExpanded(){
            JDialog dlg=new JDialog(NewtonsCradle.this,title+" — Expanded",false);
            dlg.setLayout(new BorderLayout());
            GraphPanel big=new GraphPanel(title){
                public Dimension getPreferredSize(){return new Dimension(780,520);}
            };
            syncTo(big);
            javax.swing.Timer sync=new javax.swing.Timer(30,ev->{syncTo(big);big.repaint();});
            sync.start();
            dlg.addWindowListener(new WindowAdapter(){
                public void windowClosing(WindowEvent e){sync.stop();}});
            dlg.add(big,BorderLayout.CENTER);
            dlg.pack(); dlg.setLocationRelativeTo(NewtonsCradle.this); dlg.setVisible(true);
        }

        void syncTo(GraphPanel big){
            big.t1=t1; big.s1=s1; big.t2=t2; big.s2=s2; big.showBalls=showBalls;
            if(showBalls==0)big.btnBoth.setSelected(true);
            else if(showBalls==1)big.btnRed.setSelected(true);
            else big.btnBlue.setSelected(true);
        }

        // ── Paint ─────────────────────────────────────────────────────────────
        @Override
        protected void paintComponent(Graphics g0){
            super.paintComponent(g0);
            Graphics2D g=(Graphics2D)g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int W=getWidth(), H=getHeight();
            int hH=(header!=null)?header.getHeight():0;
            int dH=H-hH; if(dH<20) return;
            g.translate(0,hH);

            if(t1==null||t1.isEmpty()){
                g.setColor(new Color(0xAAAAAA));
                g.setFont(new Font("Arial",Font.ITALIC,10));
                g.drawString("Press Play to see data",10,dH/2);
                g.translate(0,-hH); return;
            }

            int pad=48, top=4, bot=20;
            int gw=W-pad-4, gh=dH-top-bot;
            if(gw<10||gh<10){g.translate(0,-hH);return;}

            double tMin=t1.get(0), tMax=t1.get(t1.size()-1);
            if(tMax-tMin<0.01) tMax=tMin+1;
            double vMin=Double.MAX_VALUE, vMax=-Double.MAX_VALUE;
            if(showBalls!=2&&s1!=null)for(double v:s1){vMin=Math.min(vMin,v);vMax=Math.max(vMax,v);}
            if(showBalls!=1&&s2!=null)for(double v:s2){vMin=Math.min(vMin,v);vMax=Math.max(vMax,v);}
            if(vMax-vMin<1e-9){vMin-=0.01;vMax+=0.01;}

            drawAxes(g,pad,top,gw,gh,tMin,tMax,vMin,vMax);
            if(showBalls!=2) drawLine(g,t1,s1,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_RED);
            if(showBalls!=1) drawLine(g,t2,s2,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_BLUE);

            // Legend
            g.setFont(new Font("Arial",Font.BOLD,9));
            int lx=pad+3, ly=top+3;
            if(showBalls!=2){g.setColor(C_RED);g.fillRect(lx,ly,10,5);
                g.setColor(Color.DARK_GRAY);g.drawString("Red",lx+13,ly+6);lx+=50;}
            if(showBalls!=1){g.setColor(C_BLUE);g.fillRect(lx,ly,10,5);
                g.setColor(Color.DARK_GRAY);g.drawString("Blue",lx+13,ly+6);}

            // Hover crosshair
            drawCrosshair(g,pad,top,gw,gh,tMin,tMax,vMin,vMax);
            g.translate(0,-hH);
        }

        // ── Axes with nice ticks ──────────────────────────────────────────────
        double niceStep(double lo,double hi,int n){
            double raw=(hi-lo)/n;
            double mag=Math.pow(10,Math.floor(Math.log10(Math.max(raw,1e-12))));
            double[] nice={1,2,2.5,5,10};
            double best=nice[nice.length-1]*mag;
            for(double v:nice){double s=v*mag;if(s>=raw){best=s;break;}}
            return best;
        }

        void drawAxes(Graphics2D g,int pad,int top,int gw,int gh,
                      double tMin,double tMax,double vMin,double vMax){
            g.setFont(new Font("Arial",Font.PLAIN,8));
            FontMetrics fm=g.getFontMetrics();

            // Y grid + labels
            double yStep=niceStep(vMin,vMax,8);
            double yStart=Math.ceil(vMin/yStep)*yStep;
            g.setColor(new Color(0xE2E2E2));
            g.setStroke(new BasicStroke(0.4f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_BEVEL,0,new float[]{3},0));
            for(double v=yStart;v<=vMax+yStep*0.01;v+=yStep){
                int py=top+gh-(int)((v-vMin)/(vMax-vMin)*gh);
                if(py<top||py>top+gh) continue;
                g.drawLine(pad,py,pad+gw,py);
            }
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(0xAAAAAA)); g.drawRect(pad,top,gw,gh);
            for(double v=yStart;v<=vMax+yStep*0.01;v+=yStep){
                int py=top+gh-(int)((v-vMin)/(vMax-vMin)*gh);
                if(py<top-4||py>top+gh+4) continue;
                g.setColor(new Color(0x888888)); g.setStroke(new BasicStroke(1f));
                g.drawLine(pad-3,py,pad,py);
                String s=fmtVal(v);
                g.setColor(new Color(0x333333));
                g.drawString(s,pad-4-fm.stringWidth(s),py+3);
            }

            // X grid + labels
            double xStep=niceStep(tMin,tMax,7);
            double xStart=Math.ceil(tMin/xStep)*xStep;
            g.setColor(new Color(0xE2E2E2));
            g.setStroke(new BasicStroke(0.4f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_BEVEL,0,new float[]{3},0));
            for(double t=xStart;t<=tMax+xStep*0.01;t+=xStep){
                int px=pad+(int)((t-tMin)/(tMax-tMin)*gw);
                if(px<pad||px>pad+gw) continue;
                g.drawLine(px,top,px,top+gh);
            }
            for(double t=xStart;t<=tMax+xStep*0.01;t+=xStep){
                int px=pad+(int)((t-tMin)/(tMax-tMin)*gw);
                if(px<pad||px>pad+gw) continue;
                g.setColor(new Color(0x888888)); g.setStroke(new BasicStroke(1f));
                g.drawLine(px,top+gh,px,top+gh+3);
                String s=String.format("%.2f",t);
                g.setColor(new Color(0x333333));
                g.drawString(s,px-fm.stringWidth(s)/2,top+gh+12);
            }
            g.setColor(new Color(0x666666));
            g.drawString("t(s)",pad+gw+2,top+gh+12);
        }

        String fmtVal(double v){
            double a=Math.abs(v);
            if(a==0) return "0";
            if(a<0.001) return String.format("%.2e",v);
            if(a<0.01)  return String.format("%.4f",v);
            if(a<0.1)   return String.format("%.3f",v);
            if(a<10)    return String.format("%.3f",v);
            if(a<100)   return String.format("%.2f",v);
            return String.format("%.1f",v);
        }

        void drawLine(Graphics2D g,List<Double> ts,List<Double> vs,
                      double tMin,double tMax,double vMin,double vMax,
                      int pad,int top,int gw,int gh,Color c){
            if(ts==null||vs==null||ts.size()<2) return;
            g.setColor(c); g.setStroke(new BasicStroke(1.6f));
            int n=Math.min(ts.size(),vs.size());
            int[] xs=new int[n],ys=new int[n];
            for(int i=0;i<n;i++){
                xs[i]=pad+(int)((ts.get(i)-tMin)/(tMax-tMin)*gw);
                ys[i]=top+gh-(int)((vs.get(i)-vMin)/(vMax-vMin)*gh);
            }
            g.drawPolyline(xs,ys,n);
        }

        double interpolate(List<Double> ts,List<Double> vs,double t){
            if(ts==null||vs==null||ts.isEmpty()) return 0;
            int n=Math.min(ts.size(),vs.size());
            if(t<=ts.get(0)) return vs.get(0);
            if(t>=ts.get(n-1)) return vs.get(n-1);
            for(int i=1;i<n;i++){
                if(ts.get(i)>=t){
                    double t0=ts.get(i-1),t1=ts.get(i),v0=vs.get(i-1),v1=vs.get(i);
                    return v0+(v1-v0)*(t-t0)/(t1-t0);
                }
            }
            return vs.get(n-1);
        }

        void drawCrosshair(Graphics2D g,int pad,int top,int gw,int gh,
                           double tMin,double tMax,double vMin,double vMax){
            if(mouseScreenX<0) return;
            int mx=mouseScreenX;
            if(mx<pad||mx>pad+gw) return;
            double t=tMin+(double)(mx-pad)/gw*(tMax-tMin);

            g.setColor(new Color(60,60,60,120));
            g.setStroke(new BasicStroke(1f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_BEVEL,
                    0,new float[]{4,3},0));
            g.drawLine(mx,top,mx,top+gh);

            g.setFont(new Font("Arial",Font.BOLD,9));
            FontMetrics fm=g.getFontMetrics();
            int ty=top+14;

            for(int s=0;s<2;s++){
                if(s==0&&showBalls==2) continue;
                if(s==1&&showBalls==1) continue;
                List<Double> ts2=s==0?t1:t2, vs=s==0?s1:s2;
                Color c=s==0?C_RED:C_BLUE;
                String label=s==0?"Red":"Blue";
                if(ts2==null||vs==null||ts2.size()<2) continue;
                double val=interpolate(ts2,vs,t);
                int py=top+gh-(int)((val-vMin)/(vMax-vMin)*gh);
                py=Math.max(top+2,Math.min(top+gh-2,py));

                g.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),70));
                g.setStroke(new BasicStroke(0.8f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_BEVEL,
                        0,new float[]{3,3},0));
                g.drawLine(pad,py,mx,py);

                g.setColor(c); g.setStroke(new BasicStroke(1f));
                g.fillOval(mx-4,py-4,9,9);

                String tip=String.format("%s  (%.3f, %s)",label,t,fmtVal(val));
                int bw=fm.stringWidth(tip)+8, bh=13;
                int bx=mx+6; if(bx+bw>pad+gw) bx=mx-bw-4;
                g.setColor(new Color(255,255,255,220));
                g.fillRoundRect(bx,ty-10,bw,bh,4,4);
                g.setColor(new Color(0x222222));
                g.drawRoundRect(bx,ty-10,bw,bh,4,4);
                g.setColor(c.darker());
                g.drawString(tip,bx+4,ty);
                ty+=16;
            }
        }
    }

    // ── Entry ─────────────────────────────────────────────────────────────────
    public static void main(String[] args){
        try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}
        catch(Exception ignored){}
        SwingUtilities.invokeLater(NewtonsCradle::new);
    }
}