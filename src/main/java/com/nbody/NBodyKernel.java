package com.nbody;

import com.aparapi.Kernel;

/**
 * Aparapi N-Body Kernel for data-parallel gravity simulation.
 * Uses Structure of Arrays (SoA) layout to allow efficient SIMD execution on GPU/CPU.
 */
public class NBodyKernel extends Kernel {
    // Current positions (Read-only during kernel execution)
    private float[] x;
    private float[] y;
    private float[] z;
    
    // Velocities (Read-Write)
    private float[] vx;
    private float[] vy;
    private float[] vz;
    
    // Output positions for next step (Write-only during kernel execution)
    private float[] nextX;
    private float[] nextY;
    private float[] nextZ;
    
    // Masses
    private float[] mass;
    
    // Simulation parameters
    private float dt;
    private float G;
    private float softening;
    private int numBodies;

    public NBodyKernel(float[] x, float[] y, float[] z, 
                       float[] vx, float[] vy, float[] vz, 
                       float[] nextX, float[] nextY, float[] nextZ, 
                       float[] mass, float dt, float G, float softening) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.nextX = nextX;
        this.nextY = nextY;
        this.nextZ = nextZ;
        this.mass = mass;
        this.dt = dt;
        this.G = G;
        this.softening = softening;
        this.numBodies = x.length;
    }

    /**
     * Updates simulation parameters before running the kernel.
     */
    public void updateParameters(float dt, float G, float softening) {
        this.dt = dt;
        this.G = G;
        this.softening = softening;
    }

    /**
     * Sets the active arrays (useful if references are re-allocated).
     */
    public void setArrays(float[] x, float[] y, float[] z, 
                          float[] vx, float[] vy, float[] vz, 
                          float[] nextX, float[] nextY, float[] nextZ, 
                          float[] mass) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.nextX = nextX;
        this.nextY = nextY;
        this.nextZ = nextZ;
        this.mass = mass;
        this.numBodies = x.length;
    }

    @Override
    public void run() {
        int i = getGlobalId();
        if (i >= numBodies) return;

        float myX = x[i];
        float myY = y[i];
        float myZ = z[i];
        
        float myVx = vx[i];
        float myVy = vy[i];
        float myVz = vz[i];

        float fx = 0.0f;
        float fy = 0.0f;
        float fz = 0.0f;

        // Loop over all bodies to accumulate gravitational force
        for (int j = 0; j < numBodies; j++) {
            float dx = x[j] - myX;
            float dy = y[j] - myY;
            float dz = z[j] - myZ;
            
            float distSqr = dx * dx + dy * dy + dz * dz + softening;
            float dist = (float) Math.sqrt(distSqr);
            float invDist = 1.0f / dist;
            float invDist3 = invDist * invDist * invDist;
            
            // Force factor: G * mass_j / dist^3
            // Acceleration vector: factor * delta_vector
            float s = G * mass[j] * invDist3;
            fx += dx * s;
            fy += dy * s;
            fz += dz * s;
        }

        // Update velocities using simple Euler integration
        float newVx = myVx + dt * fx;
        float newVy = myVy + dt * fy;
        float newVz = myVz + dt * fz;

        vx[i] = newVx;
        vy[i] = newVy;
        vz[i] = newVz;

        // Write new positions to output buffer to prevent race conditions
        nextX[i] = myX + dt * newVx;
        nextY[i] = myY + dt * newVy;
        nextZ[i] = myZ + dt * newVz;
    }
}
