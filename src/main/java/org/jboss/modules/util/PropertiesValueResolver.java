/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.modules.util;

import java.io.File;
import java.util.Properties;

/**
 * Parses a string and replaces any references recursively to system properties
 * or environment variables in the string ${[env.]some.name:<defaultvalue>}
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public class PropertiesValueResolver {

    private static final int INITIAL = 0;
    private static final int GOT_DOLLAR = 1;
    private static final int GOT_OPEN_BRACE = 2;
    private static final int RESOLVED = 3;
    private static final int DEFAULT = 4;

    public static boolean isExpression(final String value) {
        int openIdx = value.indexOf("${");
        return openIdx > -1 && value.lastIndexOf('}') > openIdx;
    }

    /**
     * Replace properties of the form:
     * <code>${<i>&lt;[env.]name&gt;[</i>,<i>&lt;[env.]name2&gt;[</i>,<i>&lt;[env.]name3&gt;...]][</i>:<i>&lt;default&gt;]</i>}</code>
     *
     * @param value - either a system property or environment variable reference
     * @return the value of the system property or environment variable referenced
     *         if it exists
     */
    public static String replaceProperties(final String value) {
        return replaceProperties(value, System.getProperties());
    }

    /**
     * Replace properties of the form:
     * <code>${<i>&lt;[env.]name&gt;[</i>,<i>&lt;[env.]name2&gt;[</i>,<i>&lt;[env.]name3&gt;...]][</i>:<i>&lt;default&gt;]</i>}</code>
     *
     * @param value - either a system property or environment variable reference
     * @return the value of the system property or environment variable referenced
     *         if it exists
     */
    public static String replaceProperties(final String value, final Properties properties) {
        final StringBuilder builder = new StringBuilder();
        final int len = value.length();
        int state = INITIAL;
        int start = -1;
        int nest = 0;
        int nameStart = -1;
        for (int i = 0; i < len; i = value.offsetByCodePoints(i, 1)) {
            final int ch = value.codePointAt(i);
            switch (state) {
                case INITIAL: {
                    switch (ch) {
                        case '$': {
                            state = GOT_DOLLAR;
                            continue;
                        }
                        default: {
                            builder.appendCodePoint(ch);
                            continue;
                        }
                    }
                    // not reachable
                }
                case GOT_DOLLAR: {
                    switch (ch) {
                        case '$': {
                            builder.appendCodePoint(ch);
                            state = INITIAL;
                            continue;
                        }
                        case '{': {
                            start = i + 1;
                            nameStart = start;
                            state = GOT_OPEN_BRACE;
                            continue;
                        }
                        default: {
                            // invalid; emit and resume
                            builder.append('$').appendCodePoint(ch);
                            state = INITIAL;
                            continue;
                        }
                    }
                    // not reachable
                }
                case GOT_OPEN_BRACE: {
                    switch (ch) {
                        case '{': {
                            nest++;
                            continue;
                        }
                        case ':':
                            if (nameStart == i) {
                                // not a default delimiter; same as default case
                                continue;
                            }
                            // else fall into the logic for 'end of key to resolve cases' "," and "}"
                        case '}':
                        case ',': {
                            if (nest > 0) {
                                if (ch == '}') nest--;
                                continue;
                            }
                            final String name = value.substring(nameStart, i).trim();
                            if ("/".equals(name)) {
                                builder.append(File.separator);
                                state = ch == '}' ? INITIAL : RESOLVED;
                                continue;
                            } else if (":".equals(name)) {
                                builder.append(File.pathSeparator);
                                state = ch == '}' ? INITIAL : RESOLVED;
                                continue;
                            }
                            // First check for system property, then env variable
                            String val = (String) properties.get(name);
                            if (val == null && name.startsWith("env."))
                                val = System.getenv(name.substring(4));

                            if (val != null && !val.equals(value)) {
                                builder.append(val);
                                state = ch == '}' ? INITIAL : RESOLVED;
                                continue;
                            } else if (ch == ',') {
                                nameStart = i + 1;
                                continue;
                            } else if (ch == ':') {
                                start = i + 1;
                                state = DEFAULT;
                                continue;
                            } else {
                                throw new IllegalStateException("Failed to resolve expression: " + value.substring(start - 2, i + 1));
                            }
                        }
                        default: {
                            continue;
                        }
                    }
                    // not reachable
                }
                case RESOLVED: {
                    if (ch == '{') {
                        nest ++;
                    } else if (ch == '}') {
                        if (nest > 0) {
                            nest--;
                        } else {
                            state = INITIAL;
                        }
                    }
                    continue;
                }
                case DEFAULT: {
                    if (ch == '{') {
                        nest ++;
                    } else if (ch == '}') {
                        if (nest > 0) {
                            nest --;
                        } else {
                            state = INITIAL;
                            builder.append(value.substring(start, i));
                        }
                    }
                    continue;
                }
                default:
                    throw new IllegalStateException("Unexpected char seen: " + ch);
            }
        }
        switch (state) {
            case GOT_DOLLAR: {
                builder.append('$');
                break;
            }
            case DEFAULT: {
                builder.append(value.substring(start - 2));
                break;
            }
            case GOT_OPEN_BRACE: {
                // We had a reference that was not resolved, throw ISE
                throw new IllegalStateException("Incomplete expression: " + builder.toString());
            }
        }
        return builder.toString();
    }

}
