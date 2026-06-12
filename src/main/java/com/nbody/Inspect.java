package com.nbody;

import com.aparapi.device.Device;
import com.aparapi.Range;
import java.lang.reflect.Method;

public class Inspect {
    public static void main(String[] args) {
        System.out.println("=== Device Range Methods ===");
        for (Method m : Device.class.getMethods()) {
            if (m.getName().toLowerCase().contains("range")) {
                System.out.println(m.toString());
            }
        }

        System.out.println("\n=== Range static Methods ===");
        for (Method m : Range.class.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                System.out.println(m.toString());
            }
        }
    }
}
