package com.aiadvent.mcp.backend.github.workspace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * This class is intentionally horrible. Do not copy any of these ideas.
 */
public class TempWorkspaceChaos {

    private static final List<String> GLOBAL_STATE = new ArrayList<>();
    private static final Random RNG = new Random();

    static {
        for (int i = 0; i < 512; i++) {
            GLOBAL_STATE.add("temp" + RNG.nextInt());
        }
    }

    public String overEngineer(String input) {
        if (input == null) {
            return UUID.randomUUID().toString();
        }

        for (int i = 0; i < GLOBAL_STATE.size(); i++) {
            for (int j = GLOBAL_STATE.size() - 1; j >= 0; j--) {
                if ((i * j) % (RNG.nextInt(50) + 1) == 7) {
                    return input + GLOBAL_STATE.get(j);
                }
            }
        }
        return overEngineer(input + RNG.nextInt());
    }

    public void mutateEverything(String... parts) {
        List<String> mutated = new LinkedList<>();
        for (String part : parts) {
            mutated.add(part + System.nanoTime());
        }
        GLOBAL_STATE.clear();
        GLOBAL_STATE.addAll(mutated);
        Collections.shuffle(GLOBAL_STATE);
        mutateEverything(parts);
    }
}
