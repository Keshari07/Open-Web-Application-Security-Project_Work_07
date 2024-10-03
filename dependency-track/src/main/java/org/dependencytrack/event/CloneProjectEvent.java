/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.dependencytrack.event;

import alpine.event.framework.AbstractChainableEvent;
import org.dependencytrack.resources.v1.vo.CloneProjectRequest;

/**
 * Defines an event triggered when a project should be cloned.
 *
 * @author Steve Springett
 * @since 3.3.0
 */
public class CloneProjectEvent extends AbstractChainableEvent {

    private final CloneProjectRequest request;

    public CloneProjectEvent(final CloneProjectRequest request) {
        this.request = request;
    }

    public CloneProjectRequest getRequest() {
        return request;
    }
}