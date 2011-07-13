/*
 * Copyright (C) 2011, FuseSource Corp.  All rights reserved.
 * http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */
package org.fusesource.fabric.fab.util;

import java.io.File;
import java.io.IOException;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 */
public class Manifests {

    /**
     * Returns the entry from the manifest for the given name
     */
    public static String getManfiestEntry(File file, String attributeName) {
        String answer = null;
        try {
            JarFile jar = new JarFile(file);
            try {
                // only handle non OSGi jar
                Manifest manifest = jar.getManifest();
                if (manifest != null) {
                    answer = manifest.getMainAttributes().getValue(attributeName);
                }
            } finally {
                jar.close();
            }
        } catch (IOException e) {
            // TODO warn...
        }
        return answer;
    }


}
