/*******************************************************************************
 * Copyright (c) 2022 Christoph Läubrich and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Christoph Läubrich - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.p2maven.repository;

import org.apache.maven.artifact.handler.ArtifactHandler;
import org.codehaus.plexus.component.annotations.Component;
import org.eclipse.tycho.ArtifactType;

@Component(role = ArtifactHandler.class, hint = ArtifactType.TYPE_P2_ARTIFACTS)
public class P2ArtifactsArtifactHandler extends AbstractArtifactHandler {

	public P2ArtifactsArtifactHandler() {
		super(ArtifactType.TYPE_P2_ARTIFACTS, EXTENSION_XML, LANGUAGE_XML, false);
	}

}
