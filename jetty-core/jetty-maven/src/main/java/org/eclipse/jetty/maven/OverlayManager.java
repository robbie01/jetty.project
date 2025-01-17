//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.MountedPathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;

/**
 * OverlayManager
 * 
 * Mediates information about overlays configured in a war plugin.
 *
 */
public class OverlayManager
{
    private final WarPluginInfo warPlugin;

    public OverlayManager(WarPluginInfo warPlugin)
    {
        this.warPlugin = warPlugin;
    }

    public void applyOverlays(ContextHandler contextHandler, boolean append)
        throws Exception
    {
        List<Resource> resourceBases = new ArrayList<Resource>();

        for (Overlay o : getOverlays(contextHandler))
        {
            //can refer to the current project in list of overlays for ordering purposes
            if (o.getConfig() != null && o.getConfig().isCurrentProject() && contextHandler.getBaseResource().exists())
            {
                resourceBases.add(contextHandler.getBaseResource()); 
                continue;
            }
            //add in the selectively unpacked overlay in the correct order to the webapp's resource base
            resourceBases.add(unpackOverlay(contextHandler, o));
        }

        if (!resourceBases.contains(contextHandler.getBaseResource()) && contextHandler.getBaseResource().exists())
        {
            if (append)
                resourceBases.add(0, contextHandler.getBaseResource());
            else
                resourceBases.add(contextHandler.getBaseResource());
        }

        contextHandler.setBaseResource(ResourceFactory.combine(resourceBases));
    }
    
    /**
     * Generate an ordered list of overlays
     */
    private List<Overlay> getOverlays(ContextHandler contextHandler)
        throws Exception
    {
        Objects.requireNonNull(contextHandler);

        Set<Artifact> matchedWarArtifacts = new HashSet<Artifact>();
        List<Overlay> overlays = new ArrayList<Overlay>();
        
        //Check all of the overlay configurations
        for (OverlayConfig config:warPlugin.getMavenWarOverlayConfigs())
        {
            //overlays can be individually skipped
            if (config.isSkip())
                continue;

            //an empty overlay refers to the current project - important for ordering
            if (config.isCurrentProject())
            {
                Overlay overlay = new Overlay(config, null);
                overlays.add(overlay);
                continue;
            }

            //if a war matches an overlay config
            Artifact a = warPlugin.getWarArtifact(config.getGroupId(), config.getArtifactId(), config.getClassifier());
            if (a != null)
            {   
                matchedWarArtifacts.add(a);
                Resource resource = ResourceFactory.of(contextHandler).newJarFileResource(a.getFile().toPath().toUri());
                SelectiveJarResource r = new SelectiveJarResource(resource);
                r.setIncludes(config.getIncludes());
                r.setExcludes(config.getExcludes());
                Overlay overlay = new Overlay(config, r);
                overlays.add(overlay);
            }
        }

        //iterate over the left over war artifacts add them
        for (Artifact a: warPlugin.getWarArtifacts())
        {
            if (!matchedWarArtifacts.contains(a))
            {
                Resource resource = ResourceFactory.of(contextHandler).newJarFileResource(a.getFile().toPath().toUri());
                Overlay overlay = new Overlay(null, resource);
                overlays.add(overlay);
            }
        }
        return overlays;
    }
    
    /**
     * Unpack a war overlay.
     * 
     * @param overlay the war overlay to unpack
     * @return the location to which it was unpacked
     * @throws IOException if there is an IO problem
     */
    protected Resource unpackOverlay(ContextHandler contextHandler, Overlay overlay)
        throws IOException
    {
        Objects.requireNonNull(contextHandler);
        Objects.requireNonNull(overlay);

        if (overlay.getResource() == null)
            return null; //nothing to unpack

        //Get the name of the overlayed war and unpack it to a dir of the
        //same name in the temporary directory
        //We know it is a war because it came from the maven repo
        assert overlay.getResource() instanceof MountedPathResource;
        Path p = Paths.get(URIUtil.unwrapContainer(overlay.getResource().getURI()));
        String name = p.getName(p.getNameCount() - 1).toString();
        name = name.replace('.', '_');

        File overlaysDir = new File(warPlugin.getProject().getBuild().getDirectory(), "jetty_overlays");
        File dir = new File(overlaysDir, name);

        //if specified targetPath, unpack to that subdir instead
        File unpackDir = dir;
        if (overlay.getConfig() != null && overlay.getConfig().getTargetPath() != null)
            unpackDir = new File(dir, overlay.getConfig().getTargetPath());

        overlay.unpackTo(unpackDir);
        
        //use top level of unpacked content
        return ResourceFactory.of(contextHandler).newResource(unpackDir.getCanonicalPath());
    }
}
