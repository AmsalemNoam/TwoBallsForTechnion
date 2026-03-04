import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

/**
 * Newton's Cradle Physics Simulation
 * Two pendulums: Red ball (left, raised) swings and collides with Blue ball (right, at rest).
 * Real pendulum physics with proper energy transfer via coefficient of restitution.
 */
public class NewtonsCradle extends JFrame {

    // ── Materials ─────────────────────────────────────────────────────────────
    enum Material {
        //           name           e     density  base1(red) base2(blue)  hi1          hi2
        PLASTIC   ("Plastic",    0.70, 1200,
                new Color(0xD32F2F), new Color(0x1565C0),
                new Color(0xFF8A80), new Color(0x82B1FF)),
        PLASTICINE("Plasticine", 0.10, 1700,
                new Color(0xAD1457), new Color(0x2E7D32),
                new Color(0xF48FB1), new Color(0xA5D6A7)),
        METAL     ("Metal",      0.90, 7800,
                new Color(0xB71C1C), new Color(0x0D47A1),   // red vs deep blue
                new Color(0xFFCDD2), new Color(0xBBDEFB));  // light tints

        final String name;
        final double e, density;
        final Color base1, base2, hi1, hi2;
        Material(String n,double e,double d,Color b1,Color b2,Color h1,Color h2){
            name=n;this.e=e;density=d;base1=b1;base2=b2;hi1=h1;hi2=h2;
        }
    }

    enum BallSize {
        SMALL("Small", 0.034),
        LARGE("Large", 0.056);
        final String name; final double radius;
        BallSize(String n,double r){name=n;radius=r;}
    }

    // ── Physics ───────────────────────────────────────────────────────────────
    static final double PIVOT_Y  = 0.06;   // world Y of pivot (m)
    static final double STRING_L = 0.30;   // string length (m)
    static final double GRAVITY  = 9.81;
    static final double DT       = 0.003;  // time step (s)
    static final int    SUBSTEPS = 5;
    static final double DAMPING  = 0.003;  // air resistance coefficient

    // ── Pendulum ──────────────────────────────────────────────────────────────
    static class Pendulum {
        double pivotX;
        double angle;        // rad from vertical; negative = left, positive = right
        double omega;        // angular velocity rad/s
        double radius, mass;
        Color  baseColor, hiColor;
        Material material;
        boolean isRed;

        // history lists
        final List<Double> tH=new ArrayList<>(),vH=new ArrayList<>(),
                xH=new ArrayList<>(),yH=new ArrayList<>(),
                keH=new ArrayList<>(),peH=new ArrayList<>(),
                teH=new ArrayList<>(),wH=new ArrayList<>();
        double prevKE=0;  // for work calculation
        double totalWork=0;

        double ballX(){return pivotX + Math.sin(angle)*STRING_L;}
        double ballY(){return PIVOT_Y + Math.cos(angle)*STRING_L;}
        double height(){return PIVOT_Y + STRING_L - ballY();} // height above rest pos
        double speed(){return Math.abs(omega)*STRING_L;}
        double ke(){return 0.5*mass*speed()*speed();}
        double pe(){return mass*GRAVITY*height();}

        void record(double t){
            tH.add(t); vH.add(speed()); xH.add(ballX()); yH.add(ballY());
            double k=ke(), p=pe();
            keH.add(k); peH.add(p); teH.add(k+p);
            double dw = k - prevKE;   // work done on this ball this step
            totalWork += dw;
            wH.add(totalWork);
            prevKE=k;
        }
        void trim(int max){
            if(tH.size()>max){
                int c=tH.size()-max;
                tH.subList(0,c).clear();  vH.subList(0,c).clear();
                xH.subList(0,c).clear();  yH.subList(0,c).clear();
                keH.subList(0,c).clear(); peH.subList(0,c).clear();
                teH.subList(0,c).clear(); wH.subList(0,c).clear();
            }
        }
    }

    // ── App state ─────────────────────────────────────────────────────────────
    CradleCanvas  canvas;
    GraphPanel    velGraph, xGraph, yGraph, energyGraph;
    JLabel        eLabel;
    JComboBox<String> mat1Box, mat2Box, size1Box, size2Box, angleBox;
    JButton       startBtn, resetBtn;

    Pendulum p1, p2;
    javax.swing.Timer timer;
    double  simTime=0;
    boolean running=false;
    double  computedE=0;
    double  pivot1X, pivot2X;

    // ─────────────────────────────────────────────────────────────────────────
    public NewtonsCradle(){
        super("Newton's Cradle — Coefficient of Restitution");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        getContentPane().setBackground(Color.WHITE);
        setLayout(new BorderLayout(4,4));
        buildUI();
        pack();
        setMinimumSize(new Dimension(900,700));
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ── Build UI ──────────────────────────────────────────────────────────────
    void buildUI(){
        // ─── Control strip ───────────────────────────────────────────────────
        JPanel ctrl=new JPanel(new GridBagLayout());
        ctrl.setBackground(new Color(0xF0F0F0));
        ctrl.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0,0,1,0,new Color(0xCCCCCC)),
                BorderFactory.createEmptyBorder(7,10,7,10)));
        GridBagConstraints gc=new GridBagConstraints();
        gc.insets=new Insets(2,4,2,4); gc.anchor=GridBagConstraints.WEST;

        String[] mats  ={"Plastic","Plasticine","Metal"};
        String[] sizes ={"Small","Large"};
        String[] angles={"High (60°)","Medium (35°)"};

        mat1Box =combo(mats);  mat2Box =combo(mats);
        size1Box=combo(sizes); size2Box=combo(sizes);
        angleBox=combo(angles);

        ActionListener liveUpdate=e->{updateAppearanceOnly();canvas.repaint();};
        mat1Box.addActionListener(liveUpdate);  mat2Box.addActionListener(liveUpdate);
        size1Box.addActionListener(liveUpdate); size2Box.addActionListener(liveUpdate);
        angleBox.addActionListener(liveUpdate);

        // Row 0: red ball settings
        gc.gridy=0;
        gc.gridx=0; ctrl.add(lbl("Red ball:"),gc);
        gc.gridx=1; ctrl.add(mat1Box,gc);
        gc.gridx=2; ctrl.add(lbl("Size:"),gc);
        gc.gridx=3; ctrl.add(size1Box,gc);
        gc.gridx=4; ctrl.add(lbl("Release angle:"),gc);
        gc.gridx=5; ctrl.add(angleBox,gc);

        // Row 1: blue ball settings
        gc.gridy=1;
        gc.gridx=0; ctrl.add(lbl("Blue ball:"),gc);
        gc.gridx=1; ctrl.add(mat2Box,gc);
        gc.gridx=2; ctrl.add(lbl("Size:"),gc);
        gc.gridx=3; ctrl.add(size2Box,gc);

        // Row 2: info + buttons
        eLabel=new JLabel("Coefficient of Restitution (e): —");
        eLabel.setFont(new Font("Arial",Font.BOLD,12));
        eLabel.setForeground(new Color(0x222222));
        gc.gridy=2; gc.gridx=0; gc.gridwidth=4; ctrl.add(eLabel,gc);
        gc.gridwidth=1;

        startBtn=btn("▶  Play",  new Color(0x1565C0),Color.WHITE);
        resetBtn=btn("↺  Reset", new Color(0xB71C1C),Color.WHITE);
        gc.gridx=4; gc.gridy=2; ctrl.add(startBtn,gc);
        gc.gridx=5;             ctrl.add(resetBtn,gc);

        startBtn.addActionListener(e->toggleSim());
        resetBtn.addActionListener(e->resetSim());
        add(ctrl,BorderLayout.NORTH);

        // ─── Cradle canvas ────────────────────────────────────────────────────
        canvas=new CradleCanvas();
        canvas.setPreferredSize(new Dimension(900,270));
        add(canvas,BorderLayout.CENTER);

        // ─── Graph row ────────────────────────────────────────────────────────
        JPanel gRow=new JPanel(new GridLayout(1,4,5,0));
        gRow.setBackground(Color.WHITE);
        gRow.setBorder(BorderFactory.createEmptyBorder(3,6,6,6));

        velGraph   =new GraphPanel("Speed (m/s)",   false);
        xGraph     =new GraphPanel("Position X (m)",false);
        yGraph     =new GraphPanel("Position Y (m)",false);
        energyGraph=new GraphPanel("Energy & Work", true);   // energy mode

        gRow.add(velGraph); gRow.add(xGraph);
        gRow.add(yGraph);   gRow.add(energyGraph);
        gRow.setPreferredSize(new Dimension(900,210));
        add(gRow,BorderLayout.SOUTH);

        resetSim();
    }

    // ── Appearance-only update (no angle/physics reset) ───────────────────────
    void updateAppearanceOnly(){
        if(p1==null||p2==null) return;
        Material m1=Material.values()[mat1Box.getSelectedIndex()];
        Material m2=Material.values()[mat2Box.getSelectedIndex()];
        BallSize s1=BallSize.values()[size1Box.getSelectedIndex()];
        BallSize s2=BallSize.values()[size2Box.getSelectedIndex()];

        p1.material=m1; p1.baseColor=m1.base1; p1.hiColor=m1.hi1;
        p1.radius=s1.radius;
        p1.mass=m1.density*(4.0/3.0)*Math.PI*Math.pow(s1.radius,3);

        p2.material=m2; p2.baseColor=m2.base2; p2.hiColor=m2.hi2;
        p2.radius=s2.radius;
        p2.mass=m2.density*(4.0/3.0)*Math.PI*Math.pow(s2.radius,3);

        computedE=Math.sqrt(m1.e*m2.e);
        updateELabel(m1,m2);

        // Update angle too (but keep pendulums in place if running)
        if(!running){
            double gap=s1.radius+s2.radius;
            pivot1X=0.50-gap/2.0; pivot2X=0.50+gap/2.0;
            p1.pivotX=pivot1X; p2.pivotX=pivot2X;
            double ang=Math.toRadians((angleBox.getSelectedIndex()==0)?-60.0:-35.0);
            p1.angle=ang;
        }
    }

    // ── Full reset ────────────────────────────────────────────────────────────
    void resetSim(){
        if(timer!=null) timer.stop();
        running=false; startBtn.setText("▶  Play"); simTime=0;

        Material m1=Material.values()[mat1Box.getSelectedIndex()];
        Material m2=Material.values()[mat2Box.getSelectedIndex()];
        BallSize s1=BallSize.values()[size1Box.getSelectedIndex()];
        BallSize s2=BallSize.values()[size2Box.getSelectedIndex()];
        double ang=Math.toRadians((angleBox.getSelectedIndex()==0)?-60.0:-35.0);

        computedE=Math.sqrt(m1.e*m2.e);
        updateELabel(m1,m2);

        double gap=s1.radius+s2.radius;
        pivot1X=0.50-gap/2.0; pivot2X=0.50+gap/2.0;

        p1=new Pendulum(); p1.isRed=true;
        p1.pivotX=pivot1X; p1.angle=ang; p1.omega=0;
        p1.radius=s1.radius; p1.material=m1;
        p1.baseColor=m1.base1; p1.hiColor=m1.hi1;
        p1.mass=m1.density*(4.0/3.0)*Math.PI*Math.pow(s1.radius,3);
        p1.prevKE=p1.ke(); p1.totalWork=0;

        p2=new Pendulum(); p2.isRed=false;
        p2.pivotX=pivot2X; p2.angle=0; p2.omega=0;
        p2.radius=s2.radius; p2.material=m2;
        p2.baseColor=m2.base2; p2.hiColor=m2.hi2;
        p2.mass=m2.density*(4.0/3.0)*Math.PI*Math.pow(s2.radius,3);
        p2.prevKE=0; p2.totalWork=0;

        p1.record(0); p2.record(0);
        velGraph.clear(); xGraph.clear(); yGraph.clear(); energyGraph.clear();
        canvas.repaint();
    }

    void updateELabel(Material m1,Material m2){
        eLabel.setText(String.format(
                "Coefficient of Restitution (e) = %.3f   [%s vs %s]",
                computedE, m1.name, m2.name));
    }

    // ── Toggle ────────────────────────────────────────────────────────────────
    void toggleSim(){
        if(running){
            timer.stop(); running=false; startBtn.setText("▶  Play");
        } else {
            running=true; startBtn.setText("⏸  Pause");
            timer=new javax.swing.Timer(16,e->step());
            timer.start();
        }
    }

    // ── Physics step ──────────────────────────────────────────────────────────
    void step(){
        for(int s=0;s<SUBSTEPS;s++){
            integrate(p1); integrate(p2);
            resolveCollision();
        }
        simTime+=DT*SUBSTEPS;
        p1.record(simTime); p2.record(simTime);
        p1.trim(800); p2.trim(800);

        velGraph.update(p1.tH,p1.vH, p2.tH,p2.vH);
        xGraph  .update(p1.tH,p1.xH, p2.tH,p2.xH);
        yGraph  .update(p1.tH,p1.yH, p2.tH,p2.yH);
        // Energy graph: series1=KE, series2=PE, series3=TE (total), work uses wH
        energyGraph.updateEnergy(p1,p2);
        canvas.repaint();
    }

    void integrate(Pendulum p){
        // pendulum ODE: alpha = -(g/L)*sin(theta) - damping*omega
        double alpha=-(GRAVITY/STRING_L)*Math.sin(p.angle) - DAMPING*p.omega;
        p.omega+=alpha*DT;
        p.angle+=p.omega*DT;
    }

    void resolveCollision(){
        double x1=p1.ballX(), y1=p1.ballY();
        double x2=p2.ballX(), y2=p2.ballY();
        double dist=Math.hypot(x2-x1,y2-y1);
        double minD=p1.radius+p2.radius;
        if(dist<minD && dist>1e-9){
            // Contact normal
            double nx=(x2-x1)/dist, ny=(y2-y1)/dist;
            // Velocity of each ball centre in world coords
            // v_ball = omega * L * tangent;  tangent = (cos θ, -sin θ) * sign
            double v1x= p1.omega*STRING_L*Math.cos(p1.angle);
            double v1y=-p1.omega*STRING_L*Math.sin(p1.angle);
            double v2x= p2.omega*STRING_L*Math.cos(p2.angle);
            double v2y=-p2.omega*STRING_L*Math.sin(p2.angle);
            // Relative approach speed along normal
            double relV=(v1x-v2x)*nx+(v1y-v2y)*ny;
            if(relV<=0) return; // already separating
            // Impulse magnitude
            double j=-(1+computedE)*relV/(1.0/p1.mass+1.0/p2.mass);
            // Convert linear impulse change back to angular velocity
            double dv1x=-j*nx/p1.mass, dv1y=-j*ny/p1.mass;
            double dv2x= j*nx/p2.mass, dv2y= j*ny/p2.mass;
            // Project dv onto tangential direction to get d(omega)
            double t1x=Math.cos(p1.angle), t1y=-Math.sin(p1.angle);
            double t2x=Math.cos(p2.angle), t2y=-Math.sin(p2.angle);
            p1.omega-=(dv1x*t1x+dv1y*t1y)/STRING_L;
            p2.omega-=(dv2x*t2x+dv2y*t2y)/STRING_L;
            // Push apart to prevent overlap
            double overlap=minD-dist;
            double push=overlap*0.5/STRING_L;
            p1.angle-=Math.copySign(Math.asin(Math.min(0.99,push)),1);
            p2.angle+=Math.copySign(Math.asin(Math.min(0.99,push)),1);
        }
    }

    // ── Small helpers ─────────────────────────────────────────────────────────
    JLabel lbl(String t){
        JLabel l=new JLabel(t); l.setForeground(new Color(0x333333));
        l.setFont(new Font("Arial",Font.PLAIN,12)); return l;
    }
    JComboBox<String> combo(String[] items){
        JComboBox<String> c=new JComboBox<>(items);
        c.setFont(new Font("Arial",Font.PLAIN,12)); c.setBackground(Color.WHITE); return c;
    }
    JButton btn(String txt,Color bg,Color fg){
        JButton b=new JButton(txt); b.setBackground(bg); b.setForeground(fg);
        b.setFont(new Font("Arial",Font.BOLD,12)); b.setFocusPainted(false);
        b.setOpaque(true); b.setBorderPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(5,14,5,14)); return b;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Cradle Canvas
    // ════════════════════════════════════════════════════════════════════════
    class CradleCanvas extends JPanel {
        CradleCanvas(){setBackground(Color.WHITE);}

        // World→screen helpers
        int wx(double v){return (int)(v*scX+oX);}
        int wy(double v){return (int)(v*scY+oY);}
        double scX,oX,scY,oY;

        void computeScale(){
            int W=getWidth(), H=getHeight();
            scX=W*0.52; oX=W*0.24;
            scY=H*1.45; oY=-H*0.03;
        }

        @Override
        protected void paintComponent(Graphics g0){
            super.paintComponent(g0);
            if(p1==null) return;
            Graphics2D g=(Graphics2D)g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            computeScale();
            int W=getWidth(), H=getHeight();

            // ── Frame ────────────────────────────────────────────────────────
            int fy=wy(PIVOT_Y);
            int px1=wx(pivot1X), px2=wx(pivot2X);
            int maxR=(int)(Math.max(p1.radius,p2.radius)*scX);
            int barExt=maxR*4;
            int poleH=H*2/5;

            g.setColor(new Color(0x424242));
            // top bar
            g.setStroke(new BasicStroke(5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.drawLine(px1-barExt,fy,px2+barExt,fy);
            // poles + base
            g.setStroke(new BasicStroke(3f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.drawLine(px1-barExt,fy,px1-barExt,fy+poleH);
            g.drawLine(px2+barExt,fy,px2+barExt,fy+poleH);
            g.drawLine(px1-barExt,fy+poleH,px2+barExt,fy+poleH);

            // ── Rest-position ghost (dashed) for angle reference ──────────────
            if(!running && p1.angle!=0){
                // ghost string
                g.setColor(new Color(180,180,180,120));
                g.setStroke(new BasicStroke(1f,BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_BEVEL,0,new float[]{5,5},0));
                g.drawLine(px1,fy,wx(pivot1X),wy(PIVOT_Y+STRING_L));

                // arc showing angle
                g.setColor(new Color(200,60,60,150));
                g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_BEVEL,0,new float[]{5,4},0));
                int arcR=(int)(STRING_L*scY*0.38);
                int startDeg=90;
                int extDeg=(int)(-Math.toDegrees(p1.angle));
                g.drawArc(px1-arcR,fy-arcR,arcR*2,arcR*2,startDeg,extDeg);

                // angle label
                g.setFont(new Font("Arial",Font.BOLD,12));
                g.setColor(new Color(0xB71C1C));
                String aStr=String.format("θ = %.0f°",Math.abs(Math.toDegrees(p1.angle)));
                g.drawString(aStr, px1-arcR-2, fy-5);
            }

            // ── Strings ───────────────────────────────────────────────────────
            g.setColor(new Color(0x888888));
            g.setStroke(new BasicStroke(1.4f));
            g.drawLine(wx(p1.pivotX),wy(PIVOT_Y),wx(p1.ballX()),wy(p1.ballY()));
            g.drawLine(wx(p2.pivotX),wy(PIVOT_Y),wx(p2.ballX()),wy(p2.ballY()));

            // ── Balls ─────────────────────────────────────────────────────────
            paintBall(g,p2); // blue first (under)
            paintBall(g,p1); // red on top

            // ── Energy labels ─────────────────────────────────────────────────
            g.setFont(new Font("Arial",Font.PLAIN,10));
            g.setColor(new Color(0x444444));
            drawEnergyTag(g,p1,"KE=%.3f  PE=%.3f");
            drawEnergyTag(g,p2,"KE=%.3f  PE=%.3f");

            // ── Legend (top-right, clipped) ───────────────────────────────────
            paintLegend(g,W);
        }

        void paintBall(Graphics2D g,Pendulum p){
            int bx=wx(p.ballX()), by=wy(p.ballY());
            int r=Math.max(6,(int)(p.radius*scX));

            // shadow
            g.setColor(new Color(0,0,0,20));
            g.fillOval(bx-r+2,by-r+3,r*2,r*2);

            Material mat=p.material;
            g.setStroke(new BasicStroke(1f));

            if(mat==Material.METAL){
                // Metallic: angled linear gradient — clearly red vs blue
                GradientPaint gp=new GradientPaint(
                        bx-r,by-r, p.hiColor,
                        bx+r,by+r, p.baseColor);
                g.setPaint(gp); g.fillOval(bx-r,by-r,r*2,r*2);
                // vertical sheen
                g.setColor(new Color(255,255,255,100));
                g.fillOval(bx-r/3,by-r,r*2/3,r);
                // cross-hatch texture to look metallic
                g.setColor(new Color(0,0,0,18));
                g.setStroke(new BasicStroke(0.5f));
                for(int i=-r;i<r;i+=5){
                    int x1c=bx+i,y1c=by-r, x2c=bx+i,y2c=by+r;
                    if(x1c>bx-r && x1c<bx+r) g.drawLine(x1c,y1c,x2c,y2c);
                }
            } else if(mat==Material.PLASTICINE){
                // Matte flat
                g.setColor(p.baseColor); g.fillOval(bx-r,by-r,r*2,r*2);
                // stipple
                g.setColor(p.baseColor.darker());
                int sp=Math.max(r/3,3);
                for(int i=-1;i<=1;i++) for(int j=-1;j<=1;j++)
                    if((i+j)%2==0)
                        g.fillOval(bx+i*sp-2,by+j*sp-2,4,4);
                g.setColor(new Color(255,255,255,40));
                g.fillOval(bx-r/2,by-r/2,r/2,r/2);
            } else {
                // Plastic: radial shine
                RadialGradientPaint rg=new RadialGradientPaint(
                        bx-r/3f,by-r/3f,r*1.2f,
                        new float[]{0f,0.4f,1f},
                        new Color[]{p.hiColor,p.baseColor,p.baseColor.darker()});
                g.setPaint(rg); g.fillOval(bx-r,by-r,r*2,r*2);
            }

            // border
            g.setPaint(null);
            g.setColor(p.baseColor.darker().darker());
            g.setStroke(new BasicStroke(1.5f));
            g.drawOval(bx-r,by-r,r*2,r*2);

            // large-ball ring accent
            if(p.radius>BallSize.SMALL.radius+0.005){
                g.setColor(new Color(255,255,255,50));
                g.setStroke(new BasicStroke(1.8f));
                g.drawOval(bx-r+3,by-r+3,r*2-6,r*2-6);
            }
        }

        void drawEnergyTag(Graphics2D g,Pendulum p,String fmt){
            int bx=wx(p.ballX());
            int by=wy(p.ballY());
            int r=Math.max(6,(int)(p.radius*scX));
            String s=String.format("KE=%.3f J  PE=%.3f J",p.ke(),p.pe());
            g.setFont(new Font("Arial",Font.PLAIN,9));
            g.setColor(new Color(0x555555));
            g.drawString(s,bx-r,by+r+12);
        }

        void paintLegend(Graphics2D g,int W){
            int x=W-190, y=8;
            g.setFont(new Font("Arial",Font.BOLD,11));
            // Red ball
            g.setColor(p1.baseColor); g.fillOval(x,y,13,13);
            g.setColor(new Color(0x222222));
            g.drawString("Ball 1 (Red)  "+p1.material.name+
                    "  "+(p1.radius>BallSize.SMALL.radius+0.005?"Large":"Small"),x+17,y+11);
            y+=18;
            // Blue ball
            g.setColor(p2.baseColor); g.fillOval(x,y,13,13);
            g.setColor(new Color(0x222222));
            g.drawString("Ball 2 (Blue) "+p2.material.name+
                    "  "+(p2.radius>BallSize.SMALL.radius+0.005?"Large":"Small"),x+17,y+11);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Generic Graph Panel — handles both ball-data graphs and energy graph
    // ════════════════════════════════════════════════════════════════════════
    class GraphPanel extends JPanel {
        final String title;
        final boolean isEnergy;   // true → energy/work mode

        // Ball-data mode
        List<Double> t1,s1list,t2,s2list;

        // Energy mode: for each ball: KE, PE, TE, Work
        // We'll store combined data
        List<Double> tE;
        List<Double> ke1,pe1,te1,w1,ke2,pe2,te2,w2;

        // show modes
        // Ball graphs: 0=both 1=red 2=blue
        int showBalls=0;
        // Energy graph: 0=energy+work 1=energy only 2=work only
        int showEnergy=0;

        static final Color C_RED   =new Color(0xD32F2F);
        static final Color C_BLUE  =new Color(0x1565C0);
        static final Color C_KE    =new Color(0xE65100);  // orange
        static final Color C_PE    =new Color(0x2E7D32);  // green
        static final Color C_TE    =new Color(0x6A1B9A);  // purple
        static final Color C_WORK  =new Color(0x0277BD);  // teal

        JPanel header;
        JToggleButton btnBoth,btnRed,btnBlue;        // ball-mode buttons
        JToggleButton btnEW,btnEOnly,btnWOnly;        // energy-mode buttons
        JButton expandBtn;

        GraphPanel(String title,boolean isEnergy){
            this.title=title; this.isEnergy=isEnergy;
            setLayout(new BorderLayout(0,0));
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(new Color(0xCCCCCC)));
            buildHeader();
        }

        void buildHeader(){
            header=new JPanel();
            header.setLayout(new BoxLayout(header,BoxLayout.Y_AXIS));
            header.setBackground(new Color(0xEEEEEE));
            header.setBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(0xCCCCCC)));

            // Row 1: title + expand button
            JPanel row1=new JPanel(new FlowLayout(FlowLayout.LEFT,3,2));
            row1.setBackground(new Color(0xEEEEEE));
            JLabel tl=new JLabel(title);
            tl.setFont(new Font("Arial",Font.BOLD,10));
            tl.setForeground(new Color(0x222222));
            row1.add(tl);

            expandBtn=new JButton("⤢ Expand");
            expandBtn.setFont(new Font("Arial",Font.PLAIN,9));
            expandBtn.setMargin(new Insets(1,5,1,5));
            expandBtn.setFocusPainted(false);
            expandBtn.setToolTipText("Expand graph in new window");
            expandBtn.addActionListener(e->openExpanded());
            row1.add(expandBtn);
            header.add(row1);

            // Row 2: filter toggles
            JPanel row2=new JPanel(new FlowLayout(FlowLayout.LEFT,3,1));
            row2.setBackground(new Color(0xE0E0E0));

            if(!isEnergy){
                btnBoth=tog("Both",true); btnRed=tog("Red",false); btnBlue=tog("Blue",false);
                ButtonGroup bg=new ButtonGroup();
                bg.add(btnBoth);bg.add(btnRed);bg.add(btnBlue);
                row2.add(new JLabel("Show:"));
                row2.add(btnBoth);row2.add(btnRed);row2.add(btnBlue);
                btnBoth.addActionListener(e->{showBalls=0;repaint();});
                btnRed .addActionListener(e->{showBalls=1;repaint();});
                btnBlue.addActionListener(e->{showBalls=2;repaint();});
            } else {
                btnEW   =tog("Both",true);
                btnEOnly=tog("Energy",false);
                btnWOnly=tog("Work",  false);
                ButtonGroup bg=new ButtonGroup();
                bg.add(btnEW);bg.add(btnEOnly);bg.add(btnWOnly);
                JLabel sl=new JLabel("Show:");
                sl.setFont(new Font("Arial",Font.PLAIN,9));
                row2.add(sl);
                row2.add(btnEW);row2.add(btnEOnly);row2.add(btnWOnly);
                btnEW   .addActionListener(e->{showEnergy=0;repaint();});
                btnEOnly.addActionListener(e->{showEnergy=1;repaint();});
                btnWOnly.addActionListener(e->{showEnergy=2;repaint();});
            }
            header.add(row2);
            add(header,BorderLayout.NORTH);
        }

        JToggleButton tog(String txt,boolean sel){
            JToggleButton b=new JToggleButton(txt,sel);
            b.setFont(new Font("Arial",Font.PLAIN,9));
            b.setMargin(new Insets(1,4,1,4));
            b.setFocusPainted(false); return b;
        }

        void clear(){
            t1=s1list=t2=s2list=null;
            tE=ke1=pe1=te1=w1=ke2=pe2=te2=w2=null;
            repaint();
        }

        // Ball-data update
        void update(List<Double> t1,List<Double> s1,List<Double> t2,List<Double> s2){
            this.t1=new ArrayList<>(t1); this.s1list=new ArrayList<>(s1);
            this.t2=new ArrayList<>(t2); this.s2list=new ArrayList<>(s2);
            repaint();
        }

        // Energy update: grab from both pendulums
        void updateEnergy(Pendulum pa,Pendulum pb){
            if(pa.tH.isEmpty()) return;
            tE =new ArrayList<>(pa.tH);
            ke1=new ArrayList<>(pa.keH); pe1=new ArrayList<>(pa.peH);
            te1=new ArrayList<>(pa.teH); w1 =new ArrayList<>(pa.wH);
            ke2=new ArrayList<>(pb.keH); pe2=new ArrayList<>(pb.peH);
            te2=new ArrayList<>(pb.teH); w2 =new ArrayList<>(pb.wH);
            repaint();
        }

        void openExpanded(){
            JDialog dlg=new JDialog(NewtonsCradle.this,title+" — Expanded",false);
            dlg.setLayout(new BorderLayout());
            GraphPanel big=new GraphPanel(title,isEnergy){
                public Dimension getPreferredSize(){return new Dimension(760,500);}
            };
            copyDataTo(big);
            // sync toggles
            big.showBalls=showBalls; big.showEnergy=showEnergy;
            if(!isEnergy){
                if(showBalls==0)big.btnBoth.setSelected(true);
                else if(showBalls==1)big.btnRed.setSelected(true);
                else big.btnBlue.setSelected(true);
                big.btnBoth.addActionListener(e->{showBalls=0;repaint();});
                big.btnRed .addActionListener(e->{showBalls=1;repaint();});
                big.btnBlue.addActionListener(e->{showBalls=2;repaint();});
            } else {
                if(showEnergy==0)big.btnEW.setSelected(true);
                else if(showEnergy==1)big.btnEOnly.setSelected(true);
                else big.btnWOnly.setSelected(true);
                big.btnEW   .addActionListener(e->{showEnergy=0;repaint();});
                big.btnEOnly.addActionListener(e->{showEnergy=1;repaint();});
                big.btnWOnly.addActionListener(e->{showEnergy=2;repaint();});
            }
            javax.swing.Timer sync=new javax.swing.Timer(30,ev->{
                copyDataTo(big);
                big.showBalls=showBalls; big.showEnergy=showEnergy;
                big.repaint();
            });
            sync.start();
            dlg.addWindowListener(new WindowAdapter(){
                public void windowClosing(WindowEvent e){sync.stop();}});
            dlg.add(big,BorderLayout.CENTER);
            dlg.pack(); dlg.setLocationRelativeTo(NewtonsCradle.this);
            dlg.setVisible(true);
        }

        void copyDataTo(GraphPanel gp){
            if(!isEnergy){
                gp.t1=t1; gp.s1list=s1list; gp.t2=t2; gp.s2list=s2list;
            } else {
                gp.tE=tE; gp.ke1=ke1; gp.pe1=pe1; gp.te1=te1; gp.w1=w1;
                gp.ke2=ke2; gp.pe2=pe2; gp.te2=te2; gp.w2=w2;
            }
        }

        @Override
        protected void paintComponent(Graphics g0){
            super.paintComponent(g0);
            Graphics2D g=(Graphics2D)g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int W=getWidth(), H=getHeight();
            int hH=(header!=null)?header.getHeight():0;
            int dH=H-hH; if(dH<20) return;
            g.translate(0,hH);

            boolean hasData=isEnergy?(tE!=null&&!tE.isEmpty()):(t1!=null&&!t1.isEmpty());
            if(!hasData){
                g.setColor(new Color(0xAAAAAA));
                g.setFont(new Font("Arial",Font.ITALIC,10));
                g.drawString("Press Play to see data",10,dH/2);
                g.translate(0,-hH); return;
            }

            int pad=38, top=6, bot=16;
            int gw=W-pad-4, gh=dH-top-bot;
            if(gw<10||gh<10){g.translate(0,-hH);return;}

            if(!isEnergy) paintBallGraph(g,W,dH,pad,top,gw,gh);
            else          paintEnergyGraph(g,W,dH,pad,top,gw,gh);

            g.translate(0,-hH);
        }

        void paintBallGraph(Graphics2D g,int W,int dH,int pad,int top,int gw,int gh){
            List<Double> ts=t1; if(ts==null||ts.isEmpty()){g.translate(0,0);return;}
            double tMin=ts.get(0), tMax=ts.get(ts.size()-1);
            if(tMax-tMin<0.01) tMax=tMin+1;
            double vMin=Double.MAX_VALUE, vMax=-Double.MAX_VALUE;
            if(showBalls!=2&&s1list!=null) for(double v:s1list){vMin=Math.min(vMin,v);vMax=Math.max(vMax,v);}
            if(showBalls!=1&&s2list!=null) for(double v:s2list){vMin=Math.min(vMin,v);vMax=Math.max(vMax,v);}
            if(vMax<=vMin){vMin-=0.05;vMax+=0.05;}

            drawAxes(g,pad,top,gw,gh,tMin,tMax,vMin,vMax);
            if(showBalls!=2) drawLine(g,t1,s1list,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_RED);
            if(showBalls!=1) drawLine(g,t2,s2list,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_BLUE);

            // legend
            g.setFont(new Font("Arial",Font.BOLD,9));
            int lx=pad+3, ly=top+3;
            if(showBalls!=2){g.setColor(C_RED);g.fillRect(lx,ly,10,5);
                g.setColor(Color.DARK_GRAY);g.drawString("Red",lx+13,ly+6); lx+=50;}
            if(showBalls!=1){g.setColor(C_BLUE);g.fillRect(lx,ly,10,5);
                g.setColor(Color.DARK_GRAY);g.drawString("Blue",lx+13,ly+6);}
        }

        void paintEnergyGraph(Graphics2D g,int W,int dH,int pad,int top,int gw,int gh){
            if(tE==null||tE.isEmpty()) return;
            double tMin=tE.get(0), tMax=tE.get(tE.size()-1);
            if(tMax-tMin<0.01) tMax=tMin+1;

            // Collect all active values to compute range
            double vMin=Double.MAX_VALUE, vMax=-Double.MAX_VALUE;
            List<List<Double>> active=new ArrayList<>();
            if(showEnergy!=2){  // energy series
                active.add(ke1);active.add(pe1);active.add(te1);
                active.add(ke2);active.add(pe2);active.add(te2);
            }
            if(showEnergy!=1){  // work series
                active.add(w1);active.add(w2);
            }
            for(List<Double> series:active)
                if(series!=null) for(double v:series){vMin=Math.min(vMin,v);vMax=Math.max(vMax,v);}
            if(vMax<=vMin){vMin-=0.01;vMax+=0.01;}

            drawAxes(g,pad,top,gw,gh,tMin,tMax,vMin,vMax);

            if(showEnergy!=2){
                drawLine(g,tE,ke1,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_KE);
                drawLine(g,tE,pe1,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_PE);
                drawLine(g,tE,te1,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_TE);
                // dashed for ball2
                drawLineDashed(g,tE,ke2,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_KE);
                drawLineDashed(g,tE,pe2,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_PE);
                drawLineDashed(g,tE,te2,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_TE);
            }
            if(showEnergy!=1){
                drawLine(g,tE,w1,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_WORK);
                drawLineDashed(g,tE,w2,tMin,tMax,vMin,vMax,pad,top,gw,gh,C_WORK);
            }

            // legend
            g.setFont(new Font("Arial",Font.BOLD,8));
            int lx=pad+2, ly=top+2;
            String[] labs={"KE","PE","E_total","Work"};
            Color[] cols={C_KE,C_PE,C_TE,C_WORK};
            boolean[] show={showEnergy!=2,showEnergy!=2,showEnergy!=2,showEnergy!=1};
            for(int i=0;i<4;i++){
                if(!show[i]) continue;
                g.setColor(cols[i]); g.fillRect(lx,ly,8,4);
                g.setColor(Color.DARK_GRAY); g.drawString(labs[i],lx+11,ly+5);
                lx+=32;
            }
            // note about dashed
            g.setFont(new Font("Arial",Font.ITALIC,8));
            g.setColor(new Color(0x777777));
            g.drawString("solid=Red  dashed=Blue",pad+2,top+gh-2);
        }

        void drawAxes(Graphics2D g,int pad,int top,int gw,int gh,
                      double tMin,double tMax,double vMin,double vMax){
            // grid lines
            g.setColor(new Color(0xDDDDDD));
            g.setStroke(new BasicStroke(0.4f,BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL,0,new float[]{3},0));
            for(int i=0;i<=4;i++){
                int py=top+gh-(int)(i*gh/4.0); g.drawLine(pad,py,pad+gw,py);}
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(0xAAAAAA));
            g.drawRect(pad,top,gw,gh);
            // y labels
            g.setFont(new Font("Arial",Font.PLAIN,8));
            g.setColor(new Color(0x444444));
            for(int i=0;i<=4;i++){
                double v=vMin+(vMax-vMin)*i/4.0;
                int py=top+gh-(int)(i*gh/4.0);
                g.drawString(String.format("%.2f",v),1,py+3);
            }
            g.drawString("t(s)",pad+gw-14,top+gh+13);
        }

        void drawLine(Graphics2D g,List<Double> ts,List<Double> vs,
                      double tMin,double tMax,double vMin,double vMax,
                      int pad,int top,int gw,int gh,Color c){
            if(ts==null||vs==null||ts.size()<2) return;
            g.setColor(c); g.setStroke(new BasicStroke(1.6f));
            int n=Math.min(ts.size(),vs.size());
            int[] xs=new int[n], ys=new int[n];
            for(int i=0;i<n;i++){
                xs[i]=pad+(int)((ts.get(i)-tMin)/(tMax-tMin)*gw);
                ys[i]=top+gh-(int)((vs.get(i)-vMin)/(vMax-vMin)*gh);
            }
            g.drawPolyline(xs,ys,n);
        }

        void drawLineDashed(Graphics2D g,List<Double> ts,List<Double> vs,
                            double tMin,double tMax,double vMin,double vMax,
                            int pad,int top,int gw,int gh,Color c){
            if(ts==null||vs==null||ts.size()<2) return;
            g.setColor(c);
            g.setStroke(new BasicStroke(1.4f,BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL,0,new float[]{5,3},0));
            int n=Math.min(ts.size(),vs.size());
            int[] xs=new int[n], ys=new int[n];
            for(int i=0;i<n;i++){
                xs[i]=pad+(int)((ts.get(i)-tMin)/(tMax-tMin)*gw);
                ys[i]=top+gh-(int)((vs.get(i)-vMin)/(vMax-vMin)*gh);
            }
            g.drawPolyline(xs,ys,n);
        }
    }

    // ── Entry ─────────────────────────────────────────────────────────────────
    public static void main(String[] args){
        try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}
        catch(Exception ignored){}
        SwingUtilities.invokeLater(NewtonsCradle::new);
    }
}