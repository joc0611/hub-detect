/**
 * hub-detect
 *
 * Copyright (C) 2018 Black Duck Software, Inc.
 * http://www.blackducksoftware.com/
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.blackducksoftware.integration.hub.detect.workflow;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blackducksoftware.integration.hub.detect.bomtool.BomToolGroupType;
import com.blackducksoftware.integration.hub.detect.exception.DetectUserFriendlyException;
import com.blackducksoftware.integration.hub.detect.exitcode.ExitCodeType;

public class RequiredBomToolChecker {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    public class RequiredBomToolResult {
        public RequiredBomToolResult(final Set<BomToolGroupType> missingBomTools) {
            this.missingBomTools = missingBomTools;
        }

        public boolean wereBomToolsMissing() {
            return missingBomTools.size() > 0;
        }

        public Set<BomToolGroupType> getMissingBomTools() {
            return missingBomTools;
        }

        private final Set<BomToolGroupType> missingBomTools;
    }

    public RequiredBomToolResult checkForMissingBomTools(final String requiredBomToolString, final Set<BomToolGroupType> applicableBomTools) {
        final Set<BomToolGroupType> required = parseRequiredBomTools(requiredBomToolString);

        final Set<BomToolGroupType> missingBomTools = required.stream()
                .filter(it -> !applicableBomTools.contains(it))
                .collect(Collectors.toSet());

        return new RequiredBomToolResult(missingBomTools);
    }

    private Set<BomToolGroupType> parseRequiredBomTools(final String rawRequiredTypeString) {
        final Set<BomToolGroupType> required = new HashSet<>();
        final String[] rawRequiredTypes = rawRequiredTypeString.split(",");
        for (final String rawType : rawRequiredTypes) {
            try {
                final BomToolGroupType type = BomToolGroupType.valueOf(rawType.toUpperCase());
                required.add(type);
            } catch (IllegalArgumentException e){
                logger.error("Unable to parse bom tool type: " + rawType);
            }
        }
        return required;
    }
}
