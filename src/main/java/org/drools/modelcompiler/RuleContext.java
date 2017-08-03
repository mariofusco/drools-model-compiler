/*
 * Copyright 2005 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.modelcompiler;

import java.util.HashMap;
import java.util.Map;

import org.drools.core.definitions.rule.impl.RuleImpl;
import org.drools.core.rule.Pattern;
import org.drools.model.Variable;

public class RuleContext {

    private final RuleImpl rule;

    private final Map<Variable, String> patternIds = new HashMap<>();
    private final Map<Variable, Pattern> patterns = new HashMap<>();

    private int patternIndex = -1;
    private int variableIndex = -1;

    public RuleContext( RuleImpl rule ) {
        this.rule = rule;
    }

    public RuleImpl getRule() {
        return rule;
    }

    public int getNextPatternIndex() {
        return ++patternIndex;
    }

    public String getPatternId( Variable patternVariable ) {
        return patternIds.computeIfAbsent( patternVariable, v -> "$" + ++variableIndex );
    }

    public void registerPattern( Variable variable, Pattern pattern ) {
        patterns.put(variable, pattern);
    }

    public Pattern getPattern(Variable variable) {
        return patterns.get( variable );
    }

    public Object getBoundFact( Variable variable, Object[] objs ) {
        return objs[ getPattern( variable ).getOffset() ];
    }
}
