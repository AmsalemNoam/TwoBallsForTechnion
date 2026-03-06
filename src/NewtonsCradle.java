import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

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
    }

    enum BallSize {
        SMALL("Small", 0.034), LARGE("Large", 0.056);
        final String name; final double radius;
        BallSize(String n, double r) { name=n; radius=r; }
    }

    // ── Physics constants ─────────────────────────────────────────────────────
    static final double PIVOT_Y  = 0.06;
    static final double STRING_L = 0.30;
    static final double GRAVITY  = 9.81;
    static final double DT       = 0.003;
    static final int    SUBSTEPS = 5;
    static final double DAMPING  = 0.003;

    // ── Pendulum ──────────────────────────────────────────────────────────────
    static class Pendulum {
        double pivotX, angle, omega, radius, mass;
        Color  baseColor, hiColor;
        Material material;
        boolean isRed;

        // Shared origin: blue ball rest position (X and Y)
        double originX; // blue ball rest X — for relative-X recording
        double originY; // blue ball rest Y — for PE calculation

        final List<Double> tH  = new ArrayList<>(), vH  = new ArrayList<>(),
                xH  = new ArrayList<>(), yH  = new ArrayList<>(),
                rY  = new ArrayList<>(),  // height above blue rest, positive=up
                keH = new ArrayList<>(), peH = new ArrayList<>(),
                wH  = new ArrayList<>();
        double prevKE = 0, totalWork = 0;

        double ballX() { return pivotX + Math.sin(angle) * STRING_L; }
        double ballY() { return PIVOT_Y + Math.cos(angle) * STRING_L; }

        // X relative to blue ball rest position (origin)
        double relX() { return ballX() - originX; }
        // Height above blue ball rest position (origin)
        double heightAboveOrigin() { return originY - ballY(); }
        double speed()  { return Math.abs(omega) * STRING_L; }
        double ke()     { return 0.5 * mass * speed() * speed(); }
        double pe()     { return mass * GRAVITY * Math.max(0, heightAboveOrigin()); }

        void record(double t) {
            tH.add(t); vH.add(speed());
            xH.add(relX());              // X relative to blue ball origin
            yH.add(ballY());             // raw Y (kept for internal use)
            rY.add(originY - ballY());   // height above blue rest: positive = up
            double k = ke(), p = pe();
            keH.add(k); peH.add(p);
            double dw = k - prevKE;
            totalWork += dw;
            wH.add(totalWork);
            prevKE = k;
        }
        void trim(int max) {
            if (tH.size() > max) {
                int c = tH.size() - max;
                tH.subList(0,c).clear(); vH.subList(0,c).clear();
                xH.subList(0,c).clear(); yH.subList(0,c).clear();
                rY.subList(0,c).clear();
                keH.subList(0,c).clear(); peH.subList(0,c).clear();
                wH.subList(0,c).clear();
            }
        }
    }

    // ── App state ─────────────────────────────────────────────────────────────
    CradleCanvas canvas;
    GraphPanel   velGraph, xGraph, yGraph;
    EnergyGraphPanel energyGraph;
    JLabel       eLabel;
    JComboBox<String> mat1Box, mat2Box, size1Box, size2Box, angleBox;
    JButton      startBtn, resetBtn;

    Pendulum p1, p2;
    javax.swing.Timer timer;
    double  simTime = 0;
    boolean running = false;
    double  computedE = 0;
    double  pivot1X, pivot2X;

    // ─────────────────────────────────────────────────────────────────────────
    public NewtonsCradle() {
        super("Newton's Cradle — Coefficient of Restitution");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        getContentPane().setBackground(Color.WHITE);
        setLayout(new BorderLayout(4, 4));
        buildUI();
        pack();
        setMinimumSize(new Dimension(960, 720));
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ── Build UI ──────────────────────────────────────────────────────────────
    void buildUI() {
        JPanel ctrl = new JPanel(new GridBagLayout());
        ctrl.setBackground(new Color(0xF0F0F0));
        ctrl.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0,0,1,0,new Color(0xCCCCCC)),
                BorderFactory.createEmptyBorder(7,10,7,10)));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2,4,2,4);
        gc.anchor = GridBagConstraints.WEST;

        String[] mats   = {"Plastic","Plasticine","Metal"};
        String[] sizes  = {"Small","Large"};
        String[] angles = {"High (60°)","Medium (35°)"};

        mat1Box  = combo(mats);  mat2Box  = combo(mats);
        size1Box = combo(sizes); size2Box = combo(sizes);
        angleBox = combo(angles);

        ActionListener liveUpdate = e -> { updateAppearanceOnly(); canvas.repaint(); };
        mat1Box.addActionListener(liveUpdate);  mat2Box.addActionListener(liveUpdate);
        size1Box.addActionListener(liveUpdate); size2Box.addActionListener(liveUpdate);
        angleBox.addActionListener(liveUpdate);

        gc.gridy=0;
        gc.gridx=0; ctrl.add(lbl("Blue ball (at rest):"),gc);
        gc.gridx=1; ctrl.add(mat1Box,gc);
        gc.gridx=2; ctrl.add(lbl("Size:"),gc);
        gc.gridx=3; ctrl.add(size1Box,gc);

        gc.gridy=1;
        gc.gridx=0; ctrl.add(lbl("Red ball (released):"),gc);
        gc.gridx=1; ctrl.add(mat2Box,gc);
        gc.gridx=2; ctrl.add(lbl("Size:"),gc);
        gc.gridx=3; ctrl.add(size2Box,gc);
        gc.gridx=4; ctrl.add(lbl("Release angle:"),gc);
        gc.gridx=5; ctrl.add(angleBox,gc);

        eLabel = new JLabel("Coefficient of Restitution (e): —");
        eLabel.setFont(new Font("Arial",Font.BOLD,12));
        eLabel.setForeground(new Color(0x222222));
        gc.gridy=2; gc.gridx=0; gc.gridwidth=4; ctrl.add(eLabel,gc);
        gc.gridwidth=1;

        startBtn = btn("▶  Play",  new Color(0x1565C0), Color.WHITE);
        resetBtn = btn("↺  Reset", new Color(0xB71C1C), Color.WHITE);
        gc.gridx=4; gc.gridy=2; ctrl.add(startBtn,gc);
        gc.gridx=5;             ctrl.add(resetBtn,gc);

        startBtn.addActionListener(e -> toggleSim());
        resetBtn.addActionListener(e -> resetSim());
        add(ctrl, BorderLayout.NORTH);

        canvas = new CradleCanvas();
        canvas.setPreferredSize(new Dimension(960, 270));
        add(canvas, BorderLayout.CENTER);

        JPanel gRow = new JPanel(new GridLayout(1,4,5,0));
        gRow.setBackground(Color.WHITE);
        gRow.setBorder(BorderFactory.createEmptyBorder(3,6,6,6));
        velGraph    = new GraphPanel("Speed (m/s)");
        xGraph      = new GraphPanel("Position X rel. to blue ball (m)");
        yGraph      = new GraphPanel("Height above blue rest (m)");
        energyGraph = new EnergyGraphPanel();
        gRow.add(velGraph); gRow.add(xGraph); gRow.add(yGraph); gRow.add(energyGraph);
        gRow.setPreferredSize(new Dimension(960, 215));
        add(gRow, BorderLayout.SOUTH);

        resetSim();
    }

    // ── Appearance-only update (no physics reset) ─────────────────────────────
    void updateAppearanceOnly() {
        if (p1==null || p2==null) return;
        Material m1 = Material.values()[mat1Box.getSelectedIndex()];
        Material m2 = Material.values()[mat2Box.getSelectedIndex()];
        BallSize  s1 = BallSize.values()[size1Box.getSelectedIndex()];
        BallSize  s2 = BallSize.values()[size2Box.getSelectedIndex()];

        // p1=blue(left/at-rest), p2=red(right/raised)
        p1.material=m1; p1.baseColor=m1.base2; p1.hiColor=m1.hi2;
        p1.radius=s1.radius;
        p1.mass=m1.density*(4.0/3.0)*Math.PI*Math.pow(s1.radius,3);

        p2.material=m2; p2.baseColor=m2.base1; p2.hiColor=m2.hi1;
        p2.radius=s2.radius;
        p2.mass=m2.density*(4.0/3.0)*Math.PI*Math.pow(s2.radius,3);

        computedE = Math.sqrt(m1.e * m2.e);
        updateELabel(m1, m2);

        if (!running) {
            double gap = s1.radius + s2.radius;
            pivot1X = 0.50 - gap/2.0; pivot2X = 0.50 + gap/2.0;
            p1.pivotX = pivot1X; p2.pivotX = pivot2X;
            // p2=red raised to the RIGHT, p1=blue stays at rest (angle=0)
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
        BallSize  s1 = BallSize.values()[size1Box.getSelectedIndex()];
        BallSize  s2 = BallSize.values()[size2Box.getSelectedIndex()];
        // FIX: positive angle = right side
        double deg = (angleBox.getSelectedIndex()==0) ? 60.0 : 35.0;
        double ang = Math.toRadians(deg);

        computedE = Math.sqrt(m1.e * m2.e);
        updateELabel(m1, m2);

        double gap = s1.radius + s2.radius;
        pivot1X = 0.50 - gap/2.0; pivot2X = 0.50 + gap/2.0;

        // p1 = BLUE ball: left pivot, at rest (angle=0) = origin
        // p2 = RED  ball: right pivot, raised to the RIGHT (positive angle)
        double blueRestX = pivot1X;          // origin X = blue ball rest X (left pivot)
        double blueRestY = PIVOT_Y + STRING_L;

        p1 = new Pendulum(); p1.isRed=false;  // BLUE — at rest, is origin
        p1.pivotX=pivot1X; p1.angle=0; p1.omega=0;
        p1.radius=s1.radius; p1.material=m1;
        p1.baseColor=m1.base2; p1.hiColor=m1.hi2;
        p1.mass=m1.density*(4.0/3.0)*Math.PI*Math.pow(s1.radius,3);
        p1.originX=blueRestX; p1.originY=blueRestY;
        p1.prevKE=0; p1.totalWork=0;

        p2 = new Pendulum(); p2.isRed=true;   // RED — raised right
        p2.pivotX=pivot2X; p2.angle=ang; p2.omega=0;
        p2.radius=s2.radius; p2.material=m2;
        p2.baseColor=m2.base1; p2.hiColor=m2.hi1;
        p2.mass=m2.density*(4.0/3.0)*Math.PI*Math.pow(s2.radius,3);
        p2.originX=blueRestX; p2.originY=blueRestY;
        p2.prevKE=p2.ke(); p2.totalWork=0;

        p1.record(0); p2.record(0);
        velGraph.clear(); xGraph.clear(); yGraph.clear(); energyGraph.clear();
        canvas.repaint();
    }

    void updateELabel(Material m1, Material m2) {
        eLabel.setText(String.format(
                "Coefficient of Restitution (e) = %.3f   [Blue: %s  |  Red: %s]",
                computedE, m1.name, m2.name));
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
        for (int s=0; s<SUBSTEPS; s++) {
            integrate(p1); integrate(p2); resolveCollision();
        }
        simTime += DT * SUBSTEPS;
        p1.record(simTime); p2.record(simTime);
        p1.trim(800); p2.trim(800);

        // s1=RED(p2) drawn in C_RED, s2=BLUE(p1) drawn in C_BLUE
        velGraph.update(p2.tH, p2.vH, p1.tH, p1.vH);
        xGraph  .update(p2.tH, p2.xH, p1.tH, p1.xH);
        yGraph  .update(p2.tH, p2.rY, p1.tH, p1.rY);
        energyGraph.updateEnergy(p2, p1);  // p2=red=series1, p1=blue=series2
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
            double v1x= p1.omega*STRING_L*Math.cos(p1.angle);
            double v1y=-p1.omega*STRING_L*Math.sin(p1.angle);
            double v2x= p2.omega*STRING_L*Math.cos(p2.angle);
            double v2y=-p2.omega*STRING_L*Math.sin(p2.angle);
            double relV = (v1x-v2x)*nx + (v1y-v2y)*ny;
            if (relV<=0) return;
            double j = -(1+computedE)*relV / (1.0/p1.mass + 1.0/p2.mass);
            double dv1x=-j*nx/p1.mass, dv1y=-j*ny/p1.mass;
            double dv2x= j*nx/p2.mass, dv2y= j*ny/p2.mass;
            double t1x=Math.cos(p1.angle), t1y=-Math.sin(p1.angle);
            double t2x=Math.cos(p2.angle), t2y=-Math.sin(p2.angle);
            p1.omega -= (dv1x*t1x+dv1y*t1y)/STRING_L;
            p2.omega -= (dv2x*t2x+dv2y*t2y)/STRING_L;
            double overlap = minD - dist;
            double push = overlap*0.5/STRING_L;
            p1.angle -= Math.copySign(Math.asin(Math.min(0.99,push)), 1);
            p2.angle += Math.copySign(Math.asin(Math.min(0.99,push)), 1);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    JLabel lbl(String t) {
        JLabel l=new JLabel(t); l.setForeground(new Color(0x333333));
        l.setFont(new Font("Arial",Font.PLAIN,12)); return l;
    }
    JComboBox<String> combo(String[] items) {
        JComboBox<String> c = new JComboBox<>(items);
        c.setFont(new Font("Arial",Font.PLAIN,12)); c.setBackground(Color.WHITE); return c;
    }
    JButton btn(String txt, Color bg, Color fg) {
        JButton b = new JButton(txt); b.setBackground(bg); b.setForeground(fg);
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
            scX=W*0.52; oX=W*0.24; scY=H*1.45; oY=-H*0.03;
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

            // Origin marker at blue ball rest position (left pivot = p1)
            int blueRestX = wx(pivot1X);
            int blueRestY = wy(PIVOT_Y + STRING_L);
            g.setColor(new Color(0x1565C0));
            g.setStroke(new BasicStroke(1f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_BEVEL,
                    0,new float[]{3,3},0));
            g.drawLine(blueRestX-18,blueRestY,blueRestX+18,blueRestY);
            g.drawLine(blueRestX,blueRestY-18,blueRestX,blueRestY+18);
            g.setFont(new Font("Arial",Font.PLAIN,9));
            g.drawString("(0,0)",blueRestX+5,blueRestY+10);

            // Angle arc — red ball (p2) on RIGHT raised rightward
            if (!running && p2.angle!=0) {
                g.setColor(new Color(200,60,60,130));
                g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_BEVEL,
                        0,new float[]{5,4},0));
                int arcR=(int)(STRING_L*scY*0.38);
                int acx=wx(pivot2X), acy=wy(PIVOT_Y);
                g.drawArc(acx-arcR,acy-arcR,arcR*2,arcR*2,
                        90, (int)(-Math.toDegrees(p2.angle)));
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

            // Balls (blue first so red draws on top)
            paintBall(g,p2); paintBall(g,p1);

            // Legend only — NO energy labels under balls
            paintLegend(g,W);
        }

        void paintBall(Graphics2D g, Pendulum p) {
            int bx=wx(p.ballX()), by=wy(p.ballY());
            int r=Math.max(6,(int)(p.radius*scX));
            g.setColor(new Color(0,0,0,20));
            g.fillOval(bx-r+2,by-r+3,r*2,r*2);
            Material mat = p.material;
            if (mat==Material.METAL) {
                GradientPaint gp=new GradientPaint(bx-r,by-r,p.hiColor,bx+r,by+r,p.baseColor);
                g.setPaint(gp); g.fillOval(bx-r,by-r,r*2,r*2);
                g.setColor(new Color(255,255,255,100)); g.fillOval(bx-r/3,by-r,r*2/3,r);
                g.setColor(new Color(0,0,0,18)); g.setStroke(new BasicStroke(0.5f));
                for (int i=-r; i<r; i+=5) { int xc=bx+i; if(xc>bx-r&&xc<bx+r) g.drawLine(xc,by-r,xc,by+r); }
            } else if (mat==Material.PLASTICINE) {
                g.setColor(p.baseColor); g.fillOval(bx-r,by-r,r*2,r*2);
                g.setColor(p.baseColor.darker());
                int sp=Math.max(r/3,3);
                for (int i=-1;i<=1;i++) for (int j=-1;j<=1;j++)
                    if ((i+j)%2==0) g.fillOval(bx+i*sp-2,by+j*sp-2,4,4);
                g.setColor(new Color(255,255,255,40)); g.fillOval(bx-r/2,by-r/2,r/2,r/2);
            } else {
                RadialGradientPaint rg=new RadialGradientPaint(
                        bx-r/3f,by-r/3f,r*1.2f,new float[]{0f,0.4f,1f},
                        new Color[]{p.hiColor,p.baseColor,p.baseColor.darker()});
                g.setPaint(rg); g.fillOval(bx-r,by-r,r*2,r*2);
            }
            g.setPaint(null);
            g.setColor(p.baseColor.darker().darker());
            g.setStroke(new BasicStroke(1.5f)); g.drawOval(bx-r,by-r,r*2,r*2);
            if (p.radius > BallSize.SMALL.radius+0.005) {
                g.setColor(new Color(255,255,255,50));
                g.setStroke(new BasicStroke(1.8f)); g.drawOval(bx-r+3,by-r+3,r*2-6,r*2-6);
            }
        }

        void paintLegend(Graphics2D g, int W) {
            int x=W-195, y=8;
            g.setFont(new Font("Arial",Font.BOLD,11));
            g.setColor(p1.baseColor); g.fillOval(x,y,13,13);
            g.setColor(new Color(0x222222));
            g.drawString("Ball 1 (Blue) "+p1.material.name+
                    "  "+(p1.radius>BallSize.SMALL.radius+0.005?"Large":"Small"),x+17,y+11);
            y+=18;
            g.setColor(p2.baseColor); g.fillOval(x,y,13,13);
            g.setColor(new Color(0x222222));
            g.drawString("Ball 2 (Red)  "+p2.material.name+
                    "  "+(p2.radius>BallSize.SMALL.radius+0.005?"Large":"Small"),x+17,y+11);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Generic Graph Panel (Speed / X / Y)
    // ════════════════════════════════════════════════════════════════════════
    class GraphPanel extends JPanel {
        final String title;
        List<Double> t1, s1, t2, s2;
        int showBalls = 0; // 0=both 1=red 2=blue

        static final Color C_RED  = new Color(0xD32F2F);
        static final Color C_BLUE = new Color(0x1565C0);

        JPanel header;
        JToggleButton btnBoth, btnRed, btnBlue;
        JButton expandBtn;

        GraphPanel(String title) {
            this.title = title;
            setLayout(new BorderLayout(0,0));
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(new Color(0xCCCCCC)));
            buildHeader();
        }

        void buildHeader() {
            header = new JPanel();
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            header.setBackground(new Color(0xEEEEEE));
            header.setBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(0xCCCCCC)));

            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT,3,2));
            row1.setBackground(new Color(0xEEEEEE));
            JLabel tl = new JLabel(title);
            tl.setFont(new Font("Arial",Font.BOLD,10)); tl.setForeground(new Color(0x222222));
            row1.add(tl);
            expandBtn = new JButton("⤢ Expand");
            expandBtn.setFont(new Font("Arial",Font.PLAIN,9));
            expandBtn.setMargin(new Insets(1,5,1,5)); expandBtn.setFocusPainted(false);
            expandBtn.addActionListener(e -> openExpanded());
            row1.add(expandBtn);
            header.add(row1);

            JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT,3,1));
            row2.setBackground(new Color(0xE0E0E0));
            btnBoth = tog("Both",true); btnRed = tog("Red",false); btnBlue = tog("Blue",false);
            ButtonGroup bg = new ButtonGroup(); bg.add(btnBoth); bg.add(btnRed); bg.add(btnBlue);
            JLabel sl = new JLabel("Show:"); sl.setFont(new Font("Arial",Font.PLAIN,9));
            row2.add(sl); row2.add(btnBoth); row2.add(btnRed); row2.add(btnBlue);
            btnBoth.addActionListener(e -> { showBalls=0; repaint(); });
            btnRed .addActionListener(e -> { showBalls=1; repaint(); });
            btnBlue.addActionListener(e -> { showBalls=2; repaint(); });
            header.add(row2);
            add(header, BorderLayout.NORTH);
        }

        JToggleButton tog(String txt, boolean sel) {
            JToggleButton b = new JToggleButton(txt,sel);
            b.setFont(new Font("Arial",Font.PLAIN,9));
            b.setMargin(new Insets(1,4,1,4)); b.setFocusPainted(false); return b;
        }

        void clear() { t1=s1=t2=s2=null; repaint(); }

        void update(List<Double> t1,List<Double> s1,List<Double> t2,List<Double> s2) {
            this.t1=new ArrayList<>(t1); this.s1=new ArrayList<>(s1);
            this.t2=new ArrayList<>(t2); this.s2=new ArrayList<>(s2);
            repaint();
        }

        void openExpanded() {
            JDialog dlg = new JDialog(NewtonsCradle.this, title+" — Expanded", false);
            dlg.setLayout(new BorderLayout());
            GraphPanel big = new GraphPanel(title) {
                public Dimension getPreferredSize() { return new Dimension(760,500); }
            };
            big.t1=t1; big.s1=s1; big.t2=t2; big.s2=s2; big.showBalls=showBalls;
            if (showBalls==0) big.btnBoth.setSelected(true);
            else if (showBalls==1) big.btnRed.setSelected(true);
            else big.btnBlue.setSelected(true);
            big.btnBoth.addActionListener(e -> { showBalls=0; repaint(); });
            big.btnRed .addActionListener(e -> { showBalls=1; repaint(); });
            big.btnBlue.addActionListener(e -> { showBalls=2; repaint(); });
            javax.swing.Timer sync = new javax.swing.Timer(30, ev -> {
                big.t1=t1; big.s1=s1; big.t2=t2; big.s2=s2;
                big.showBalls=showBalls; big.repaint();
            });
            sync.start();
            dlg.addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) { sync.stop(); }});
            dlg.add(big, BorderLayout.CENTER);
            dlg.pack(); dlg.setLocationRelativeTo(NewtonsCradle.this); dlg.setVisible(true);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D)g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int W=getWidth(), H=getHeight();
            int hH = (header!=null) ? header.getHeight() : 0;
            int dH = H-hH; if (dH<20) return;
            g.translate(0,hH);

            if (t1==null || t1.isEmpty()) {
                g.setColor(new Color(0xAAAAAA));
                g.setFont(new Font("Arial",Font.ITALIC,10));
                g.drawString("Press Play to see data",10,dH/2);
                g.translate(0,-hH); return;
            }

            int pad=40, top=5, bot=18;
            int gw=W-pad-4, gh=dH-top-bot;
            if (gw<10||gh<10) { g.translate(0,-hH); return; }

            double tMin=t1.get(0), tMax=t1.get(t1.size()-1);
            if (tMax-tMin<0.01) tMax=tMin+1;
            double vMin=Double.MAX_VALUE, vMax=-Double.MAX_VALUE;
            if (showBalls!=2&&s1!=null) for(double v:s1){vMin=Math.min(vMin,v);vMax=Math.max(vMax,v);}
            if (showBalls!=1&&s2!=null) for(double v:s2){vMin=Math.min(vMin,v);vMax=Math.max(vMax,v);}
            if (vMax<=vMin) { vMin-=0.05; vMax+=0.05; }

            drawAxes(g,pad,top,gw,gh,tMin,tMax,vMin,vMax);
            if (showBalls!=2) drawLine(g,t1,s1,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_RED,false);
            if (showBalls!=1) drawLine(g,t2,s2,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_BLUE,false);

            g.setFont(new Font("Arial",Font.BOLD,9));
            int lx=pad+3, ly=top+3;
            if (showBalls!=2) { g.setColor(C_RED); g.fillRect(lx,ly,10,5);
                g.setColor(Color.DARK_GRAY); g.drawString("Red (ball 2)",lx+13,ly+6); lx+=75; }
            if (showBalls!=1) { g.setColor(C_BLUE); g.fillRect(lx,ly,10,5);
                g.setColor(Color.DARK_GRAY); g.drawString("Blue (ball 1)",lx+13,ly+6); }
            g.translate(0,-hH);
        }

        void drawAxes(Graphics2D g,int pad,int top,int gw,int gh,
                      double tMin,double tMax,double vMin,double vMax) {
            g.setColor(new Color(0xDDDDDD));
            g.setStroke(new BasicStroke(0.4f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_BEVEL,
                    0,new float[]{3},0));
            for (int i=0;i<=4;i++) {
                int py=top+gh-(int)(i*gh/4.0); g.drawLine(pad,py,pad+gw,py);
            }
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(0xAAAAAA)); g.drawRect(pad,top,gw,gh);
            g.setFont(new Font("Arial",Font.PLAIN,8)); g.setColor(new Color(0x444444));
            for (int i=0;i<=4;i++) {
                double v=vMin+(vMax-vMin)*i/4.0;
                int py=top+gh-(int)(i*gh/4.0);
                g.drawString(String.format("%.2f",v),1,py+3);
            }
            g.drawString("t(s)",pad+gw-14,top+gh+15);
        }

        void drawLine(Graphics2D g,List<Double> ts,List<Double> vs,
                      double tMin,double tMax,double vMin,double vMax,
                      int pad,int top,int gw,int gh,Color c,boolean dashed) {
            if (ts==null||vs==null||ts.size()<2) return;
            if (dashed) g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL,0,new float[]{5,3},0));
            else g.setStroke(new BasicStroke(1.6f));
            g.setColor(c);
            int n=Math.min(ts.size(),vs.size());
            int[] xs=new int[n], ys=new int[n];
            for (int i=0;i<n;i++) {
                xs[i]=pad+(int)((ts.get(i)-tMin)/(tMax-tMin)*gw);
                ys[i]=top+gh-(int)((vs.get(i)-vMin)/(vMax-vMin)*gh);
            }
            g.drawPolyline(xs,ys,n);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Energy Graph Panel
    // Origin (0,0) = blue ball rest position
    // Checkboxes: KE Red, PE Red, KE Blue, PE Blue, Work Red, Work Blue
    // View mode: Both | Energy only | Work only
    // ════════════════════════════════════════════════════════════════════════
    class EnergyGraphPanel extends GraphPanel {
        List<Double> tE, ke1, pe1, ke2, pe2, w1, w2;
        int viewMode = 0; // 0=both 1=energy 2=work

        JCheckBox cbKE1, cbPE1, cbKE2, cbPE2, cbW1, cbW2;
        JToggleButton btnEW, btnEOnly, btnWOnly;

        final Color CKE1_R = new Color(0xD32F2F); // KE Red   — red
        final Color CPE1_R = new Color(0xFF8F00); // PE Red   — amber
        final Color CKE2_B = new Color(0x1565C0); // KE Blue  — blue
        final Color CPE2_B = new Color(0x00838F); // PE Blue  — teal
        final Color CW1_R  = new Color(0xAD1457); // Work Red — magenta
        final Color CW2_B  = new Color(0x2E7D32); // Work Blue— green
        // aliases used in drawLine calls
        final Color CKE1 = CKE1_R, CPE1 = CPE1_R;
        final Color CKE2 = CKE2_B, CPE2 = CPE2_B;
        final Color CW1  = CW1_R,  CW2  = CW2_B;

        EnergyGraphPanel() {
            super("Energy & Work (J)");
            // Remove base header, build energy-specific one
            remove(header);
            buildEnergyHeader();
        }

        void buildEnergyHeader() {
            JPanel hdr = new JPanel();
            hdr.setLayout(new BoxLayout(hdr, BoxLayout.Y_AXIS));
            hdr.setBackground(new Color(0xEEEEEE));
            hdr.setBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(0xCCCCCC)));

            // Row 1: title + view toggles + expand
            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT,3,2));
            row1.setBackground(new Color(0xEEEEEE));
            JLabel tl = new JLabel("Energy & Work (J)");
            tl.setFont(new Font("Arial",Font.BOLD,10)); tl.setForeground(new Color(0x222222));
            row1.add(tl);

            btnEW    = etog("Both",   true);
            btnEOnly = etog("Energy", false);
            btnWOnly = etog("Work",   false);
            ButtonGroup bg = new ButtonGroup(); bg.add(btnEW); bg.add(btnEOnly); bg.add(btnWOnly);
            JLabel sl = new JLabel("View:"); sl.setFont(new Font("Arial",Font.PLAIN,9));
            row1.add(sl); row1.add(btnEW); row1.add(btnEOnly); row1.add(btnWOnly);

            // Expand button — same as other graphs
            JButton exp = new JButton("⤢ Expand");
            exp.setFont(new Font("Arial",Font.PLAIN,9));
            exp.setMargin(new Insets(1,5,1,5)); exp.setFocusPainted(false);
            exp.addActionListener(e -> openEnergyExpanded());
            row1.add(exp);
            hdr.add(row1);

            // Row 2: per-series checkboxes
            JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT,4,1));
            row2.setBackground(new Color(0xE0E0E0));
            cbKE1 = ecb("KE Red",   CKE1_R, true);
            cbPE1 = ecb("PE Red",   CPE1_R, true);
            cbKE2 = ecb("KE Blue",  CKE2_B, true);
            cbPE2 = ecb("PE Blue",  CPE2_B, true);
            cbW1  = ecb("Work Red", CW1_R,  true);
            cbW2  = ecb("Work Blue",CW2_B,  true);
            row2.add(cbKE1); row2.add(cbPE1); row2.add(cbKE2);
            row2.add(cbPE2); row2.add(cbW1);  row2.add(cbW2);
            hdr.add(row2);

            btnEW   .addActionListener(e -> { viewMode=0; syncCheckboxVisibility(); repaint(); });
            btnEOnly.addActionListener(e -> { viewMode=1; syncCheckboxVisibility(); repaint(); });
            btnWOnly.addActionListener(e -> { viewMode=2; syncCheckboxVisibility(); repaint(); });
            ActionListener cl = e -> repaint();
            cbKE1.addActionListener(cl); cbPE1.addActionListener(cl);
            cbKE2.addActionListener(cl); cbPE2.addActionListener(cl);
            cbW1.addActionListener(cl);  cbW2.addActionListener(cl);

            add(hdr, BorderLayout.NORTH);
            header = hdr;
        }

        JToggleButton etog(String txt, boolean sel) {
            JToggleButton b = new JToggleButton(txt,sel);
            b.setFont(new Font("Arial",Font.PLAIN,9));
            b.setMargin(new Insets(1,4,1,4)); b.setFocusPainted(false); return b;
        }
        JCheckBox ecb(String txt, Color c, boolean sel) {
            JCheckBox cb = new JCheckBox(txt,sel);
            cb.setFont(new Font("Arial",Font.BOLD,9));
            cb.setForeground(c); cb.setBackground(new Color(0xE0E0E0));
            cb.setFocusPainted(false); return cb;
        }

        void syncCheckboxVisibility() {
            boolean se=(viewMode!=2), sw=(viewMode!=1);
            cbKE1.setVisible(se); cbPE1.setVisible(se);
            cbKE2.setVisible(se); cbPE2.setVisible(se);
            cbW1.setVisible(sw);  cbW2.setVisible(sw);
            if (header!=null) { header.revalidate(); header.repaint(); }
        }

        @Override void clear() { tE=ke1=pe1=ke2=pe2=w1=w2=null; repaint(); }

        void updateEnergy(Pendulum pa, Pendulum pb) {
            if (pa.tH.isEmpty()) return;
            tE =new ArrayList<>(pa.tH);
            ke1=new ArrayList<>(pa.keH); pe1=new ArrayList<>(pa.peH); w1=new ArrayList<>(pa.wH);
            ke2=new ArrayList<>(pb.keH); pe2=new ArrayList<>(pb.peH); w2=new ArrayList<>(pb.wH);
            repaint();
        }

        void openEnergyExpanded() {
            JDialog dlg = new JDialog(NewtonsCradle.this,"Energy & Work — Expanded",false);
            dlg.setLayout(new BorderLayout());
            EnergyGraphPanel big = new EnergyGraphPanel() {
                public Dimension getPreferredSize() { return new Dimension(760,520); }
            };
            copyTo(big); syncExpandedToggles(big);

            javax.swing.Timer sync = new javax.swing.Timer(30, ev -> {
                copyTo(big); big.viewMode=viewMode;
                big.cbKE1.setSelected(cbKE1.isSelected()); big.cbPE1.setSelected(cbPE1.isSelected());
                big.cbKE2.setSelected(cbKE2.isSelected()); big.cbPE2.setSelected(cbPE2.isSelected());
                big.cbW1.setSelected(cbW1.isSelected());   big.cbW2.setSelected(cbW2.isSelected());
                big.repaint();
            });
            sync.start();
            dlg.addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) { sync.stop(); }});
            dlg.add(big, BorderLayout.CENTER);
            dlg.pack(); dlg.setLocationRelativeTo(NewtonsCradle.this); dlg.setVisible(true);
        }

        void copyTo(EnergyGraphPanel g) {
            g.tE=tE; g.ke1=ke1; g.pe1=pe1; g.ke2=ke2; g.pe2=pe2; g.w1=w1; g.w2=w2;
        }
        void syncExpandedToggles(EnergyGraphPanel big) {
            big.viewMode=viewMode;
            if (viewMode==0) big.btnEW.setSelected(true);
            else if (viewMode==1) big.btnEOnly.setSelected(true);
            else big.btnWOnly.setSelected(true);
            big.cbKE1.setSelected(cbKE1.isSelected()); big.cbPE1.setSelected(cbPE1.isSelected());
            big.cbKE2.setSelected(cbKE2.isSelected()); big.cbPE2.setSelected(cbPE2.isSelected());
            big.cbW1.setSelected(cbW1.isSelected());   big.cbW2.setSelected(cbW2.isSelected());
            big.syncCheckboxVisibility();
            // Sync view toggle back
            big.btnEW   .addActionListener(e -> { viewMode=0; syncCheckboxVisibility(); repaint(); });
            big.btnEOnly.addActionListener(e -> { viewMode=1; syncCheckboxVisibility(); repaint(); });
            big.btnWOnly.addActionListener(e -> { viewMode=2; syncCheckboxVisibility(); repaint(); });
            big.cbKE1.addActionListener(e -> { cbKE1.setSelected(big.cbKE1.isSelected()); repaint(); });
            big.cbPE1.addActionListener(e -> { cbPE1.setSelected(big.cbPE1.isSelected()); repaint(); });
            big.cbKE2.addActionListener(e -> { cbKE2.setSelected(big.cbKE2.isSelected()); repaint(); });
            big.cbPE2.addActionListener(e -> { cbPE2.setSelected(big.cbPE2.isSelected()); repaint(); });
            big.cbW1 .addActionListener(e -> { cbW1.setSelected(big.cbW1.isSelected()); repaint(); });
            big.cbW2 .addActionListener(e -> { cbW2.setSelected(big.cbW2.isSelected()); repaint(); });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            // Paint background manually (don't call super which would draw base graph)
            g0.setColor(Color.WHITE);
            g0.fillRect(0,0,getWidth(),getHeight());

            Graphics2D g = (Graphics2D)g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int W=getWidth(), H=getHeight();
            int hH = (header!=null) ? header.getHeight() : 0;
            int dH = H-hH; if (dH<20) return;
            g.translate(0,hH);

            if (tE==null || tE.isEmpty()) {
                g.setColor(new Color(0xAAAAAA));
                g.setFont(new Font("Arial",Font.ITALIC,10));
                g.drawString("Press Play to see data",10,dH/2);
                g.translate(0,-hH); return;
            }

            int pad=42, top=5, bot=18;
            int gw=W-pad-4, gh=dH-top-bot;
            if (gw<10||gh<10) { g.translate(0,-hH); return; }

            // Collect active series for range
            List<List<Double>> active = new ArrayList<>();
            if (viewMode!=2) {
                if (cbKE1.isSelected()) active.add(ke1);
                if (cbPE1.isSelected()) active.add(pe1);
                if (cbKE2.isSelected()) active.add(ke2);
                if (cbPE2.isSelected()) active.add(pe2);
            }
            if (viewMode!=1) {
                if (cbW1.isSelected()) active.add(w1);
                if (cbW2.isSelected()) active.add(w2);
            }

            double tMin=tE.get(0), tMax=tE.get(tE.size()-1);
            if (tMax-tMin<0.01) tMax=tMin+1;
            double vMin=0, vMax=0.001;
            for (List<Double> s:active) if (s!=null)
                for (double v:s) { vMin=Math.min(vMin,v); vMax=Math.max(vMax,v); }
            vMin=Math.min(vMin,0);
            if (vMax-vMin<0.001) vMax=vMin+0.01;

            drawAxes(g,pad,top,gw,gh,tMin,tMax,vMin,vMax);

            // Zero line (blue ball rest = origin)
            if (vMin<0 && vMax>0) {
                int zy=top+gh-(int)((0-vMin)/(vMax-vMin)*gh);
                g.setColor(new Color(0,0,0,50)); g.setStroke(new BasicStroke(1f));
                g.drawLine(pad,zy,pad+gw,zy);
                g.setFont(new Font("Arial",Font.ITALIC,8));
                g.setColor(new Color(0x1565C0));
                g.drawString("0 — blue ball rest",pad+2,zy-2);
            }

            // Draw active series
            if (viewMode!=2) {
                if (cbKE1.isSelected()) drawLine(g,tE,ke1,tMin,tMax,vMin,vMax,pad,top,gw,gh,CKE1,false);
                if (cbPE1.isSelected()) drawLine(g,tE,pe1,tMin,tMax,vMin,vMax,pad,top,gw,gh,CPE1,false);
                if (cbKE2.isSelected()) drawLine(g,tE,ke2,tMin,tMax,vMin,vMax,pad,top,gw,gh,CKE2,true);
                if (cbPE2.isSelected()) drawLine(g,tE,pe2,tMin,tMax,vMin,vMax,pad,top,gw,gh,CPE2,true);
            }
            if (viewMode!=1) {
                if (cbW1.isSelected()) drawLine(g,tE,w1,tMin,tMax,vMin,vMax,pad,top,gw,gh,CW1,false);
                if (cbW2.isSelected()) drawLine(g,tE,w2,tMin,tMax,vMin,vMax,pad,top,gw,gh,CW2,true);
            }

            // Y axis label
            g.setFont(new Font("Arial",Font.PLAIN,8));
            g.setColor(new Color(0x444444));
            g.drawString("J",2,top+8);
            g.drawString("t(s)",pad+gw-14,top+gh+15);

            // Inline legend at bottom right of plot
            g.setFont(new Font("Arial",Font.BOLD,8));
            int lx=pad+4, ly=top+gh-12;
            Color[] lc={CKE1,CPE1,CKE2,CPE2,CW1,CW2};
            String[] ln={"KE(R)","PE(R)","KE(B)","PE(B)","W(R)","W(B)"};
            boolean[] lb={viewMode!=2&&cbKE1.isSelected(), viewMode!=2&&cbPE1.isSelected(),
                    viewMode!=2&&cbKE2.isSelected(), viewMode!=2&&cbPE2.isSelected(),
                    viewMode!=1&&cbW1.isSelected(),  viewMode!=1&&cbW2.isSelected()};
            for (int i=0;i<6;i++) {
                if (!lb[i]) continue;
                g.setColor(lc[i]); g.fillRect(lx,ly,8,4);
                g.setColor(Color.DARK_GRAY); g.drawString(ln[i],lx+10,ly+5);
                lx+=34;
            }

            g.translate(0,-hH);
        }
    }

    // ── Entry ─────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(NewtonsCradle::new);
    }
}