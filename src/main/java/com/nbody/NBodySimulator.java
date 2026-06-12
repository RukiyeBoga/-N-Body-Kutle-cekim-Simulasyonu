package com.nbody;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import com.aparapi.Kernel;
import com.aparapi.device.Device;
import com.aparapi.Range;
import com.aparapi.internal.kernel.KernelManager;

/**
 * Main application class for the N-Body Simulation.
 * Implements a Swing GUI to show the visualizer, controls, and real-time SIMD performance metrics.
 */
public class NBodySimulator {

    public enum ExecutionMode {
        GPU_SIMD("GPU (SIMD)"),
        CPU_MULTICORE("CPU (Çok Çekirdekli)"),
        CPU_SEQUENTIAL("CPU (Sıralı)");

        private final String name;
        ExecutionMode(String name) {
            this.name = name;
        }
        @Override
        public String toString() {
            return name;
        }
    }

    public enum Preset {
        GALAXY_COLLISION("Galaksi Çarpışması"),
        SOLAR_SYSTEM("Güneş Sistemi"),
        RANDOM_CLOUD("Rastgele Bulut");

        private final String name;
        Preset(String name) {
            this.name = name;
        }
        @Override
        public String toString() {
            return name;
        }
    }

    // Window size (increased for more breathing room)
    private static final int WIDTH = 1300;
    private static final int HEIGHT = 840;

    // Simulation Data (SoA - Structure of Arrays)
    private int numBodies = 3500; // Default count
    private float[] x;
    private float[] y;
    private float[] z;
    private float[] vx;
    private float[] vy;
    private float[] vz;
    private float[] nextX;
    private float[] nextY;
    private float[] nextZ;
    private float[] mass;

    // Parameters
    private float dt = 0.01f;
    private float G = 1.0f;
    private float softening = 2.0f; // Epsilon to avoid division by zero

    // Execution state
    private ExecutionMode currentMode = ExecutionMode.GPU_SIMD;
    private Preset currentPreset = Preset.GALAXY_COLLISION;
    private boolean isRunning = true;
    private double averagePhysTimeMs = 0.0;
    private double baselineSequentialTimeMs = 1.0;
    private double currentFps = 0.0;

    // 3D View parameters
    private float rotX = 0.2f;
    private float rotY = 0.5f;
    private float zoom = 1.2f;
    private final float cameraDistance = 800f;

    // UI Components
    private JFrame frame;
    private SimulationCanvas canvas;
    private JLabel lblFpsVal;
    private JLabel lblPhysTimeVal;
    private JLabel lblSpeedupVal;
    private JLabel lblModeVal;
    private JLabel lblBaselineVal;
    
    // UI fields for programmatic reset
    private JSlider sliderCount;
    private JSlider sliderDt;
    private JSlider sliderG;
    private JRadioButton rbtnGpu;
    private JRadioButton rbtnCpuJtp;
    private JRadioButton rbtnCpuSeq;
    
    // Dummy field to prevent JIT optimization during baseline sampling
    @SuppressWarnings("unused")
    private volatile float dummySum;

    // Aparapi Kernel
    private NBodyKernel kernel;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // Keep default cross-platform Java look & feel to ensure custom colors render identically
            new NBodySimulator().initialize();
        });
    }

    public void initialize() {
        allocateArrays();
        applyPreset(currentPreset);
        measureSequentialBaseline();

        kernel = new NBodyKernel(x, y, z, vx, vy, vz, nextX, nextY, nextZ, mass, dt, G, softening);

        setupGUI();

        // Start Simulation Loop Thread
        Thread simThread = new Thread(this::runSimulationLoop);
        simThread.setName("SimulationLoop");
        simThread.start();
    }

    private void allocateArrays() {
        x = new float[numBodies];
        y = new float[numBodies];
        z = new float[numBodies];
        vx = new float[numBodies];
        vy = new float[numBodies];
        vz = new float[numBodies];
        nextX = new float[numBodies];
        nextY = new float[numBodies];
        nextZ = new float[numBodies];
        mass = new float[numBodies];
    }

    private void applyPreset(Preset preset) {
        synchronized (this) {
            java.util.Random rand = new java.util.Random(42);

            if (preset == Preset.GALAXY_COLLISION) {
                int n1 = numBodies / 2;

                // Galaxy 1 Center
                float cx1 = -160f, cy1 = 0f, cz1 = 0f;
                float cvx1 = 1.6f, cvy1 = 0f, cvz1 = 0f;
                mass[0] = 3000f;
                x[0] = cx1; y[0] = cy1; z[0] = cz1;
                vx[0] = cvx1; vy[0] = cvy1; vz[0] = cvz1;

                for (int i = 1; i < n1; i++) {
                    float r = 20f + rand.nextFloat() * 100f;
                    float theta = rand.nextFloat() * 2f * (float)Math.PI;
                    x[i] = cx1 + r * (float)Math.cos(theta);
                    y[i] = cy1 + r * (float)Math.sin(theta);
                    z[i] = cz1 + (rand.nextFloat() - 0.5f) * 10f;
                    mass[i] = 0.1f + rand.nextFloat() * 0.9f;

                    float v = (float) Math.sqrt(G * mass[0] / r);
                    vx[i] = cvx1 - v * (float)Math.sin(theta);
                    vy[i] = cvy1 + v * (float)Math.cos(theta);
                    vz[i] = cvz1 + (rand.nextFloat() - 0.5f) * 0.2f;
                }

                // Galaxy 2 Center
                int idx2 = n1;
                float cx2 = 160f, cy2 = 0f, cz2 = 0f;
                float cvx2 = -1.6f, cvy2 = 0f, cvz2 = 0f;
                mass[idx2] = 3000f;
                x[idx2] = cx2; y[idx2] = cy2; z[idx2] = cz2;
                vx[idx2] = cvx2; vy[idx2] = cvy2; vz[idx2] = cvz2;

                for (int i = idx2 + 1; i < numBodies; i++) {
                    float r = 20f + rand.nextFloat() * 100f;
                    float theta = rand.nextFloat() * 2f * (float)Math.PI;
                    x[i] = cx2 + r * (float)Math.cos(theta);
                    y[i] = cy2 + r * (float)Math.sin(theta);
                    z[i] = cz2 + (rand.nextFloat() - 0.5f) * 10f;
                    mass[i] = 0.1f + rand.nextFloat() * 0.9f;

                    float v = (float) Math.sqrt(G * mass[idx2] / r);
                    vx[i] = cvx2 + v * (float)Math.sin(theta);
                    vy[i] = cvy2 - v * (float)Math.cos(theta);
                    vz[i] = cvz2 + (rand.nextFloat() - 0.5f) * 0.2f;
                }

            } else if (preset == Preset.SOLAR_SYSTEM) {
                mass[0] = 15000f; // Sun
                x[0] = 0; y[0] = 0; z[0] = 0;
                vx[0] = 0; vy[0] = 0; vz[0] = 0;

                for (int i = 1; i < numBodies; i++) {
                    float r = 30f + (float) Math.pow(rand.nextFloat(), 1.5) * 350f;
                    float theta = rand.nextFloat() * 2f * (float)Math.PI;
                    x[i] = r * (float)Math.cos(theta);
                    y[i] = r * (float)Math.sin(theta);
                    z[i] = (rand.nextFloat() - 0.5f) * 4f;
                    mass[i] = 0.05f + rand.nextFloat() * 2.0f;

                    float v = (float) Math.sqrt(G * mass[0] / r);
                    vx[i] = -v * (float)Math.sin(theta);
                    vy[i] = v * (float)Math.cos(theta);
                    vz[i] = (rand.nextFloat() - 0.5f) * 0.1f;
                }

            } else if (preset == Preset.RANDOM_CLOUD) {
                for (int i = 0; i < numBodies; i++) {
                    float r = rand.nextFloat() * 180f;
                    float theta = rand.nextFloat() * (float)Math.PI;
                    float phi = rand.nextFloat() * 2f * (float)Math.PI;

                    x[i] = r * (float)Math.sin(theta) * (float)Math.cos(phi);
                    y[i] = r * (float)Math.sin(theta) * (float)Math.sin(phi);
                    z[i] = r * (float)Math.cos(theta);

                    vx[i] = (rand.nextFloat() - 0.5f) * 0.4f;
                    vy[i] = (rand.nextFloat() - 0.5f) * 0.4f;
                    vz[i] = (rand.nextFloat() - 0.5f) * 0.4f;
                    mass[i] = 2.0f;
                }
            }

            System.arraycopy(x, 0, nextX, 0, numBodies);
            System.arraycopy(y, 0, nextY, 0, numBodies);
            System.arraycopy(z, 0, nextZ, 0, numBodies);

            if (kernel != null) {
                kernel.setArrays(x, y, z, vx, vy, vz, nextX, nextY, nextZ, mass);
            }
        }
    }

    private double measureSequentialBaselineSampled(int sampleSize) {
        int m = Math.min(numBodies, sampleSize);
        long start = System.nanoTime();
        float totalFx = 0.0f;
        for (int i = 0; i < m; i++) {
            float myX = x[i];
            float myY = y[i];
            float myZ = z[i];
            float fx = 0.0f;
            float fy = 0.0f;
            float fz = 0.0f;
            for (int j = 0; j < numBodies; j++) {
                float dx = x[j] - myX;
                float dy = y[j] - myY;
                float dz = z[j] - myZ;
                float distSqr = dx * dx + dy * dy + dz * dz + softening;
                float dist = (float) Math.sqrt(distSqr);
                float invDist = 1.0f / dist;
                float invDist3 = invDist * invDist * invDist;
                float s = G * mass[j] * invDist3;
                fx += dx * s;
                fy += dy * s;
                fz += dz * s;
            }
            totalFx += fx + fy + fz;
        }
        this.dummySum = totalFx; // Prevent dead code elimination
        long end = System.nanoTime();
        double sampleTimeMs = (end - start) / 1_000_000.0;
        return sampleTimeMs * ((double) numBodies / m);
    }

    private void measureSequentialBaseline() {
        System.out.println("Sıralı CPU Baz Performans Değeri Örneklemeyle Hesaplanıyor...");
        
        // Warm up JIT compiler
        measureSequentialBaselineSampled(100);
        
        double totalTime = 0;
        int steps = 3;
        for (int step = 0; step < steps; step++) {
            totalTime += measureSequentialBaselineSampled(500); // Sample 500 particles
        }
        
        baselineSequentialTimeMs = totalTime / steps;
        System.out.printf("[BİLGİ] N=%d için Sıralı CPU Baz Süresi: %.2f ms%n", numBodies, baselineSequentialTimeMs);
        
        if (lblBaselineVal != null) {
            lblBaselineVal.setText(String.format("<html>%.1f ms <font size='2' color='#888888'>(N=%d)</font></html>", baselineSequentialTimeMs, numBodies));
        }
    }

    private void runSequentialCPU() {
        for (int i = 0; i < numBodies; i++) {
            float myX = x[i];
            float myY = y[i];
            float myZ = z[i];
            float myVx = vx[i];
            float myVy = vy[i];
            float myVz = vz[i];

            float fx = 0.0f;
            float fy = 0.0f;
            float fz = 0.0f;

            for (int j = 0; j < numBodies; j++) {
                float dx = x[j] - myX;
                float dy = y[j] - myY;
                float dz = z[j] - myZ;
                
                float distSqr = dx * dx + dy * dy + dz * dz + softening;
                float dist = (float) Math.sqrt(distSqr);
                float invDist = 1.0f / dist;
                float invDist3 = invDist * invDist * invDist;
                
                float s = G * mass[j] * invDist3;
                fx += dx * s;
                fy += dy * s;
                fz += dz * s;
            }

            float newVx = myVx + dt * fx;
            float newVy = myVy + dt * fy;
            float newVz = myVz + dt * fz;

            vx[i] = newVx;
            vy[i] = newVy;
            vz[i] = newVz;

            nextX[i] = myX + dt * newVx;
            nextY[i] = myY + dt * newVy;
            nextZ[i] = myZ + dt * newVz;
        }
    }

    private void runSimulationLoop() {
        long lastFpsTime = System.nanoTime();
        int fpsCount = 0;

        while (isRunning) {
            long frameStart = System.nanoTime();

            long physStart = System.nanoTime();
            synchronized (this) {
                if (currentMode == ExecutionMode.GPU_SIMD) {
                    kernel.updateParameters(dt, G, softening);
                    Device device = null;
                    for (Device d : KernelManager.instance().getDefaultPreferences().getPreferredDevices(kernel)) {
                        if (d.getType() == Device.TYPE.GPU) {
                            device = d;
                            break;
                        }
                    }
                    if (device == null) {
                        java.util.List<Device> preferred = KernelManager.instance().getDefaultPreferences().getPreferredDevices(kernel);
                        if (preferred != null && !preferred.isEmpty()) {
                            device = preferred.get(0);
                        }
                    }
                    if (device != null) {
                        kernel.execute(device.createRange(numBodies, 250));
                    } else {
                        kernel.execute(numBodies);
                    }
                } else if (currentMode == ExecutionMode.CPU_MULTICORE) {
                    kernel.updateParameters(dt, G, softening);
                    Device device = null;
                    for (Device d : KernelManager.instance().getDefaultPreferences().getPreferredDevices(kernel)) {
                        if (d.getType() == Device.TYPE.JTP) {
                            device = d;
                            break;
                        }
                    }
                    if (device != null) {
                        kernel.execute(device.createRange(numBodies, 250));
                    } else {
                        kernel.execute(numBodies);
                    }
                } else {
                    runSequentialCPU();
                }

                System.arraycopy(nextX, 0, x, 0, numBodies);
                System.arraycopy(nextY, 0, y, 0, numBodies);
                System.arraycopy(nextZ, 0, z, 0, numBodies);
            }
            long physEnd = System.nanoTime();
            double physTimeMs = (physEnd - physStart) / 1_000_000.0;

            averagePhysTimeMs = averagePhysTimeMs * 0.9 + physTimeMs * 0.1;

            canvas.repaint();

            long frameEnd = System.nanoTime();
            double totalFrameTimeMs = (frameEnd - frameStart) / 1_000_000.0;

            fpsCount++;
            long now = System.nanoTime();
            if (now - lastFpsTime >= 1_000_000_000L) {
                currentFps = fpsCount * 1_000_000_000.0 / (now - lastFpsTime);
                fpsCount = 0;
                lastFpsTime = now;

                updateUILabels();

                double speedup = baselineSequentialTimeMs / averagePhysTimeMs;
                System.out.printf("[KONSOL] Mod: %-18s | Fizik: %6.2f ms | FPS: %5.1f | Hızlanma: %5.1fx%n",
                        currentMode, averagePhysTimeMs, currentFps, speedup);
            }

            long sleepTime = 16 - (long)totalFrameTimeMs;
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    private void updateUILabels() {
        SwingUtilities.invokeLater(() -> {
            lblFpsVal.setText(String.format("%.1f", currentFps));
            lblPhysTimeVal.setText(String.format("%.2f ms", averagePhysTimeMs));
            
            double speedup = baselineSequentialTimeMs / averagePhysTimeMs;
            if (currentMode == ExecutionMode.CPU_SEQUENTIAL) {
                lblSpeedupVal.setText("1.0x (Referans)");
                lblSpeedupVal.setForeground(Color.LIGHT_GRAY);
            } else {
                lblSpeedupVal.setText(String.format("%.1fx", speedup));
                if (speedup >= 10.0) {
                    lblSpeedupVal.setForeground(new Color(255, 50, 150)); // Bright Pink
                } else if (speedup >= 2.0) {
                    lblSpeedupVal.setForeground(new Color(255, 80, 180)); // Pink-magenta
                } else {
                    lblSpeedupVal.setForeground(Color.WHITE);
                }
            }
        });
    }

    private void setupGUI() {
        frame = new JFrame("SIMD N-Body Yerçekimi Simülatörü (Aparapi)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(WIDTH, HEIGHT);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(new Color(10, 10, 18));

        canvas = new SimulationCanvas();
        canvas.setPreferredSize(new Dimension(WIDTH - 320, HEIGHT));
        mainPanel.add(canvas, BorderLayout.CENTER);

        JPanel sidebar = createSidebar();
        mainPanel.add(sidebar, BorderLayout.EAST);

        frame.add(mainPanel);
        frame.setVisible(true);
    }

    /**
     * Helper to create panels with clean solid borders and top headers instead of layout-breaking TitledBorders.
     */
    private JPanel createSectionPanel(String title) {
        JPanel panel = new JPanel();
        panel.setBackground(new Color(25, 25, 40));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(50, 50, 75), 1),
                BorderFactory.createEmptyBorder(10, 15, 10, 15)
        ));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        titleLabel.setForeground(new Color(255, 80, 180));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(10));
        
        return panel;
    }

    /**
     * Custom JButton paint implementation to bypass Look-and-Feel rendering issues (e.g. white buttons on Windows).
     */
    private JButton createStyledButton(String text, boolean isAccent) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                Color bg;
                if (isAccent) {
                    if (getModel().isPressed()) {
                        bg = new Color(180, 20, 110);
                    } else if (getModel().isRollover()) {
                        bg = new Color(255, 90, 200);
                    } else {
                        bg = new Color(230, 40, 150); // Pink accent
                    }
                } else {
                    if (getModel().isPressed()) {
                        bg = new Color(32, 32, 48);
                    } else if (getModel().isRollover()) {
                        bg = new Color(55, 55, 80);
                    } else {
                        bg = new Color(42, 42, 62);
                    }
                }
                
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                
                g2.setColor(isAccent ? new Color(255, 80, 180) : new Color(65, 65, 95));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                
                g2.dispose();
                
                FontMetrics fm = g.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(getText())) / 2;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g.setColor(Color.WHITE);
                g.setFont(getFont());
                g.drawString(getText(), x, y);
            }
        };
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setForeground(Color.WHITE);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JPanel createSidebar() {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(320, HEIGHT));
        panel.setBackground(new Color(20, 20, 32));
        panel.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(50, 50, 75)));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        panel.add(Box.createVerticalStrut(15));

        // Title
        JLabel titleLabel = new JLabel("N-BODY SIMULATOR");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        titleLabel.setForeground(new Color(255, 60, 180)); // Hot pink title
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(titleLabel);

        JLabel subtitleLabel = new JLabel("SIMD Mimarisi & Aparapi");
        subtitleLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        subtitleLabel.setForeground(Color.GRAY);
        subtitleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(subtitleLabel);

        panel.add(Box.createVerticalStrut(20));

        // Section 1: Execution Mode
        JPanel modePanel = createSectionPanel("YÜRÜTME MODU");
        modePanel.setMaximumSize(new Dimension(290, 130));

        ButtonGroup modeGroup = new ButtonGroup();
        rbtnGpu = new JRadioButton(ExecutionMode.GPU_SIMD.name);
        rbtnCpuJtp = new JRadioButton(ExecutionMode.CPU_MULTICORE.name);
        rbtnCpuSeq = new JRadioButton(ExecutionMode.CPU_SEQUENTIAL.name);

        JRadioButton[] rbtns = { rbtnGpu, rbtnCpuJtp, rbtnCpuSeq };
        ExecutionMode[] modes = { ExecutionMode.GPU_SIMD, ExecutionMode.CPU_MULTICORE, ExecutionMode.CPU_SEQUENTIAL };

        for (int i = 0; i < 3; i++) {
            final JRadioButton btn = rbtns[i];
            final ExecutionMode mode = modes[i];
            btn.setForeground(Color.WHITE);
            btn.setBackground(new Color(25, 25, 40));
            btn.setOpaque(false);
            btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            btn.setFocusPainted(false);
            btn.setAlignmentX(Component.LEFT_ALIGNMENT);
            if (mode == currentMode) {
                btn.setSelected(true);
            }
            btn.addActionListener(e -> {
                synchronized (this) {
                    currentMode = mode;
                    System.out.println("[KULLANICI] Yürütme Modu Değiştirildi: " + currentMode);
                }
                lblModeVal.setText(currentMode.name);
            });
            modeGroup.add(btn);
            modePanel.add(btn);
        }
        panel.add(modePanel);
        panel.add(Box.createVerticalStrut(12));

        // Section 2: Real-time Performance Metrics
        JPanel metricsPanel = createSectionPanel("CANLI PERFORMANS");
        metricsPanel.setMaximumSize(new Dimension(290, 150));
        
        JPanel grid = new JPanel(new GridLayout(5, 2, 5, 6));
        grid.setBackground(new Color(25, 25, 40));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);

        Font labelFont = new Font("Segoe UI", Font.PLAIN, 12);
        Font valFont = new Font("Segoe UI", Font.BOLD, 12);

        grid.add(createStyledLabel("Aktif Mod:", labelFont, Color.LIGHT_GRAY));
        lblModeVal = createStyledLabel(currentMode.name, valFont, new Color(255, 80, 180));
        grid.add(lblModeVal);

        grid.add(createStyledLabel("Kare Hızı (FPS):", labelFont, Color.LIGHT_GRAY));
        lblFpsVal = createStyledLabel("0.0", valFont, Color.WHITE);
        grid.add(lblFpsVal);

        grid.add(createStyledLabel("Fizik Hesaplama:", labelFont, Color.LIGHT_GRAY));
        lblPhysTimeVal = createStyledLabel("0.00 ms", valFont, Color.WHITE);
        grid.add(lblPhysTimeVal);

        grid.add(createStyledLabel("Sıralı Referansı:", labelFont, Color.LIGHT_GRAY));
        lblBaselineVal = createStyledLabel("Calculating...", valFont, Color.LIGHT_GRAY);
        grid.add(lblBaselineVal);

        grid.add(createStyledLabel("Hızlanma Oranı:", labelFont, Color.LIGHT_GRAY));
        lblSpeedupVal = createStyledLabel("Evaluating...", valFont, Color.WHITE);
        grid.add(lblSpeedupVal);

        lblBaselineVal.setText(String.format("<html>%.1f ms <font size='2' color='#888888'>(N=%d)</font></html>", baselineSequentialTimeMs, numBodies));
        
        metricsPanel.add(grid);
        panel.add(metricsPanel);
        panel.add(Box.createVerticalStrut(12));

        // Section 3: Presets (Compact horizontal grid to save vertical space)
        JPanel presetsPanel = createSectionPanel("HAZIR SENARYOLAR");
        presetsPanel.setMaximumSize(new Dimension(290, 80));

        JPanel presetGrid = new JPanel(new GridLayout(1, 3, 6, 6));
        presetGrid.setBackground(new Color(25, 25, 40));
        presetGrid.setOpaque(false);
        presetGrid.setAlignmentX(Component.LEFT_ALIGNMENT);

        for (Preset p : Preset.values()) {
            String shortName = p.name;
            if (p == Preset.GALAXY_COLLISION) shortName = "Galaksi";
            else if (p == Preset.SOLAR_SYSTEM) shortName = "Sistem";
            else if (p == Preset.RANDOM_CLOUD) shortName = "Bulut";

            JButton btn = createStyledButton(shortName, false);
            btn.setPreferredSize(new Dimension(80, 28));
            btn.addActionListener(e -> {
                currentPreset = p;
                System.out.println("[KULLANICI] Senaryo Uygulandı: " + currentPreset);
                applyPreset(currentPreset);
            });
            presetGrid.add(btn);
        }
        presetsPanel.add(presetGrid);
        panel.add(presetsPanel);
        panel.add(Box.createVerticalStrut(12));

        // Section 4: Parameters Sliders
        JPanel slidersPanel = createSectionPanel("PARAMETRELER");
        slidersPanel.setMaximumSize(new Dimension(290, 200));

        // Particle count
        JLabel lblCount = new JLabel("Parçacık Sayısı (N): " + numBodies);
        lblCount.setForeground(Color.LIGHT_GRAY);
        lblCount.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblCount.setAlignmentX(Component.LEFT_ALIGNMENT);
        slidersPanel.add(lblCount);

        sliderCount = new JSlider(500, 25000, numBodies);
        sliderCount.setBackground(new Color(25, 25, 40));
        sliderCount.setOpaque(false);
        sliderCount.setForeground(Color.WHITE);
        sliderCount.setAlignmentX(Component.LEFT_ALIGNMENT);
        sliderCount.setSnapToTicks(true);
        sliderCount.setMajorTickSpacing(2500); // 2500 major ticks for 25000 range
        sliderCount.addChangeListener(e -> {
            if (!sliderCount.getValueIsAdjusting()) {
                synchronized (this) {
                    numBodies = (sliderCount.getValue() / 500) * 500; // Snap to 500 increment
                    if (numBodies < 500) numBodies = 500;
                    lblCount.setText("Parçacık Sayısı (N): " + numBodies);
                    allocateArrays();
                    applyPreset(currentPreset);
                    measureSequentialBaseline();
                }
            }
        });
        slidersPanel.add(sliderCount);
        slidersPanel.add(Box.createVerticalStrut(8));

        // Time step
        JLabel lblDt = new JLabel("Zaman Adımı (dt): " + dt);
        lblDt.setForeground(Color.LIGHT_GRAY);
        lblDt.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblDt.setAlignmentX(Component.LEFT_ALIGNMENT);
        slidersPanel.add(lblDt);

        sliderDt = new JSlider(1, 50, (int)(dt * 1000f));
        sliderDt.setBackground(new Color(25, 25, 40));
        sliderDt.setOpaque(false);
        sliderDt.setForeground(Color.WHITE);
        sliderDt.setAlignmentX(Component.LEFT_ALIGNMENT);
        sliderDt.addChangeListener(e -> {
            synchronized (this) {
                dt = sliderDt.getValue() / 1000f;
                lblDt.setText("Zaman Adımı (dt): " + dt);
            }
        });
        slidersPanel.add(sliderDt);
        slidersPanel.add(Box.createVerticalStrut(8));

        // Gravity
        JLabel lblG = new JLabel("Çekim Gücü (G): " + G);
        lblG.setForeground(Color.LIGHT_GRAY);
        lblG.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblG.setAlignmentX(Component.LEFT_ALIGNMENT);
        slidersPanel.add(lblG);

        sliderG = new JSlider(1, 100, (int)(G * 10f));
        sliderG.setBackground(new Color(25, 25, 40));
        sliderG.setOpaque(false);
        sliderG.setForeground(Color.WHITE);
        sliderG.setAlignmentX(Component.LEFT_ALIGNMENT);
        sliderG.addChangeListener(e -> {
            synchronized (this) {
                G = sliderG.getValue() / 10f;
                lblG.setText("Çekim Gücü (G): " + G);
            }
        });
        slidersPanel.add(sliderG);

        panel.add(slidersPanel);
        panel.add(Box.createVerticalStrut(15));

        // Camera and simulation control (resets everything back to the initial startup state)
        JButton btnResetCam = createStyledButton("Sistemi Sıfırla", true);
        btnResetCam.setPreferredSize(new Dimension(290, 32));
        btnResetCam.setMaximumSize(new Dimension(290, 32));
        btnResetCam.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnResetCam.addActionListener(e -> {
            synchronized (this) {
                // 1. Reset camera parameters
                rotX = 0.2f;
                rotY = 0.5f;
                zoom = 1.2f;

                // 2. Reset physics variables
                dt = 0.01f;
                G = 1.0f;
                softening = 2.0f;
                numBodies = 3500;
                currentMode = ExecutionMode.GPU_SIMD;
                currentPreset = Preset.GALAXY_COLLISION;
            }

            // 3. Reset UI component values programmatically (triggers listeners to re-allocate and re-apply presets)
            sliderCount.setValue(3500);
            sliderDt.setValue(10); // 0.01 * 1000
            sliderG.setValue(10);  // 1.0 * 10
            rbtnGpu.setSelected(true);
            lblModeVal.setText(ExecutionMode.GPU_SIMD.name);

            // 4. Forces fresh preset allocation & baseline recalculation
            applyPreset(Preset.GALAXY_COLLISION);
            measureSequentialBaseline();

            canvas.repaint();
            System.out.println("[KULLANICI] Tüm Sistem İlk Açılış Haline Sıfırlandı!");
        });
        panel.add(btnResetCam);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JLabel createStyledLabel(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    /**
     * Custom Canvas panel to render 3D-projected particles in dark mode with trail effects.
     */
    private class SimulationCanvas extends JPanel {
        private BufferedImage trailImage;
        private Point lastMousePos;

        public SimulationCanvas() {
            setBackground(new Color(10, 10, 18));
            setDoubleBuffered(true);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    lastMousePos = e.getPoint();
                }
            });

            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (lastMousePos != null) {
                        int dx = e.getX() - lastMousePos.x;
                        int dy = e.getY() - lastMousePos.y;
                        rotY += dx * 0.005f;
                        rotX += dy * 0.005f;
                        lastMousePos = e.getPoint();
                    }
                }
            });

            addMouseWheelListener(e -> {
                zoom -= e.getPreciseWheelRotation() * 0.08f * zoom;
                zoom = Math.max(0.1f, Math.min(10.0f, zoom));
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth();
            int h = getHeight();

            if (w <= 0 || h <= 0) return;

            if (trailImage == null || trailImage.getWidth() != w || trailImage.getHeight() != h) {
                trailImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D tg = trailImage.createGraphics();
                tg.setColor(new Color(10, 10, 18));
                tg.fillRect(0, 0, w, h);
                tg.dispose();
            }

            Graphics2D g2d = trailImage.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
            g2d.setColor(new Color(10, 10, 18));
            g2d.fillRect(0, 0, w, h);
            g2d.setComposite(AlphaComposite.SrcOver);

            float cosX = (float) Math.cos(rotX);
            float sinX = (float) Math.sin(rotX);
            float cosY = (float) Math.cos(rotY);
            float sinY = (float) Math.sin(rotY);

            float[] localX, localY, localZ;
            float[] localVx, localVy, localVz;
            int count;
            synchronized (NBodySimulator.this) {
                count = numBodies;
                localX = new float[count];
                localY = new float[count];
                localZ = new float[count];
                localVx = new float[count];
                localVy = new float[count];
                localVz = new float[count];
                System.arraycopy(x, 0, localX, 0, count);
                System.arraycopy(y, 0, localY, 0, count);
                System.arraycopy(z, 0, localZ, 0, count);
                System.arraycopy(vx, 0, localVx, 0, count);
                System.arraycopy(vy, 0, localVy, 0, count);
                System.arraycopy(vz, 0, localVz, 0, count);
            }

            for (int i = 0; i < count; i++) {
                float px = localX[i];
                float py = localY[i];
                float pz = localZ[i];

                float rY_x = px * cosY - pz * sinY;
                float rY_z = px * sinY + pz * cosY;

                float rX_y = py * cosX - rY_z * sinX;
                float rX_z = py * sinX + rY_z * cosX;

                float zDepth = rX_z + cameraDistance;
                if (zDepth <= 50f) continue;

                float perspective = cameraDistance / zDepth;
                int screenX = (int) (w / 2f + rY_x * perspective * zoom);
                int screenY = (int) (h / 2f + rX_y * perspective * zoom);

                if (screenX < 0 || screenX >= w || screenY < 0 || screenY >= h) continue;

                int size = Math.max(1, (int) (2.5f * perspective * (1.0f + 0.1f * zoom)));

                float speedSqr = localVx[i]*localVx[i] + localVy[i]*localVy[i] + localVz[i]*localVz[i];
                float speed = (float) Math.sqrt(speedSqr);
                
                float maxSpeed = 12.0f;
                float t = Math.min(1.0f, speed / maxSpeed);

                int red, green, blue;
                if (t < 0.5f) {
                    float tLocal = t * 2f;
                    // Interpolate from Lavender (200, 160, 255) to Hot Pink (255, 60, 180)
                    red = (int) (200 + tLocal * 55);
                    green = (int) (160 - tLocal * 100);
                    blue = (int) (255 - tLocal * 75);
                } else {
                    float tLocal = (t - 0.5f) * 2f;
                    // Interpolate from Hot Pink (255, 60, 180) to Pastel White-Pink (255, 220, 255)
                    red = 255;
                    green = (int) (60 + tLocal * 160);
                    blue = (int) (180 + tLocal * 75);
                }

                g2d.setColor(new Color(red, green, blue, Math.min(255, (int)(180 + 75 * perspective))));
                
                if (size > 2) {
                    g2d.fillOval(screenX - size / 2, screenY - size / 2, size, size);
                } else {
                    g2d.fillRect(screenX, screenY, 1, 1);
                }
            }

            g2d.dispose();

            g.drawImage(trailImage, 0, 0, null);

            Graphics2D overlayG = (Graphics2D) g;
            overlayG.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            overlayG.setColor(new Color(255, 255, 255, 120));
            overlayG.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            overlayG.drawString("Döndürmek için sürükleyin. Yakınlaşmak için kaydırın.", 15, h - 20);
            overlayG.drawString("Senaryo: " + currentPreset.name, 15, 25);
        }
    }
}
