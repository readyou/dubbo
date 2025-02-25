/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.utils;

import org.apache.dubbo.common.beanutil.JavaBeanSerializeUtil;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.dubbo.common.constants.CommonConstants.CLASS_DESERIALIZE_ALLOWED_LIST;
import static org.apache.dubbo.common.constants.CommonConstants.CLASS_DESERIALIZE_BLOCKED_LIST;
import static org.apache.dubbo.common.constants.CommonConstants.CLASS_DESERIALIZE_BLOCK_ALL;
import static org.apache.dubbo.common.constants.CommonConstants.SERIALIZE_BLOCKED_LIST_FILE_PATH;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.COMMON_IO_EXCEPTION;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.PROTOCOL_UNSAFE_SERIALIZATION;

public class SerializeClassChecker {
    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(SerializeClassChecker.class);

    private static volatile SerializeClassChecker INSTANCE = null;

    private final boolean OPEN_CHECK_CLASS;
    private final boolean BLOCK_ALL_CLASS_EXCEPT_ALLOW;
    private final Set<String> CLASS_DESERIALIZE_ALLOWED_SET = new ConcurrentHashSet<>();
    private final Set<String> CLASS_DESERIALIZE_BLOCKED_SET = new ConcurrentHashSet<>();

    private final Object CACHE = new Object();
    private final LFUCache<String, Object> CLASS_ALLOW_LFU_CACHE = new LFUCache<>();
    private final LFUCache<String, Object> CLASS_BLOCK_LFU_CACHE = new LFUCache<>();

    private final AtomicLong counter = new AtomicLong(0);

    private SerializeClassChecker() {
        String openCheckClass = System.getProperty(CommonConstants.CLASS_DESERIALIZE_OPEN_CHECK, "true");
        OPEN_CHECK_CLASS = Boolean.parseBoolean(openCheckClass);

        String blockAllClassExceptAllow = System.getProperty(CLASS_DESERIALIZE_BLOCK_ALL, "false");

        BLOCK_ALL_CLASS_EXCEPT_ALLOW = Boolean.parseBoolean(blockAllClassExceptAllow);

        String[] lines;
        try {
            ClassLoader classLoader = ClassUtils.getClassLoader(JavaBeanSerializeUtil.class);
            if (classLoader != null) {
                lines = IOUtils.readLines(classLoader.getResourceAsStream(SERIALIZE_BLOCKED_LIST_FILE_PATH));
            } else {
                lines = IOUtils.readLines(ClassLoader.getSystemResourceAsStream(SERIALIZE_BLOCKED_LIST_FILE_PATH));
            }
            for (String line : lines) {
                line = line.trim();
                if (StringUtils.isEmpty(line) || line.startsWith("#")) {
                    continue;
                }
                CLASS_DESERIALIZE_BLOCKED_SET.add(line);
            }

        } catch (IOException e) {
            logger.error(COMMON_IO_EXCEPTION, "", "", "Failed to load blocked class list! Will ignore default blocked list.", e);
        }

        String allowedClassList = System.getProperty(CLASS_DESERIALIZE_ALLOWED_LIST, "").trim().toLowerCase(Locale.ROOT);
        String blockedClassList = System.getProperty(CLASS_DESERIALIZE_BLOCKED_LIST, "").trim().toLowerCase(Locale.ROOT);

        if (StringUtils.isNotEmpty(allowedClassList)) {
            String[] classStrings = allowedClassList.trim().split(",");
            CLASS_DESERIALIZE_ALLOWED_SET.addAll(Arrays.asList(classStrings));
        }

        if (StringUtils.isNotEmpty(blockedClassList)) {
            String[] classStrings = blockedClassList.trim().split(",");
            CLASS_DESERIALIZE_BLOCKED_SET.addAll(Arrays.asList(classStrings));
        }

    }

    public static SerializeClassChecker getInstance() {
        if (INSTANCE == null) {
            synchronized (SerializeClassChecker.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SerializeClassChecker();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * For ut only
     */
    @Deprecated
    protected static void clearInstance() {
        INSTANCE = null;
    }

    /**
     * Check if a class is in block list, using prefix match
     *
     * @param name class name ( all are convert to lower case )
     * @throws IllegalArgumentException if class is blocked
     */
    public void validateClass(String name) {
        validateClass(name, true);
    }

    public boolean validateClass(String name, boolean failOnError) {
        if (!OPEN_CHECK_CLASS) {
            return true;
        }

        name = name.toLowerCase(Locale.ROOT);
        if (CACHE == CLASS_ALLOW_LFU_CACHE.get(name)) {
            return true;
        }

        if (CACHE == CLASS_BLOCK_LFU_CACHE.get(name)) {
            if (failOnError) {
                error(name);
            }
            return false;
        }

        for (String allowedPrefix : CLASS_DESERIALIZE_ALLOWED_SET) {
            if (name.startsWith(allowedPrefix)) {
                CLASS_ALLOW_LFU_CACHE.put(name, CACHE);
                return true;
            }
        }

        for (String blockedPrefix : CLASS_DESERIALIZE_BLOCKED_SET) {
            if (BLOCK_ALL_CLASS_EXCEPT_ALLOW || name.startsWith(blockedPrefix)) {
                CLASS_BLOCK_LFU_CACHE.put(name, CACHE);
                if (failOnError) {
                    error(name);
                }
                return false;
            }
        }

        CLASS_ALLOW_LFU_CACHE.put(name, CACHE);
        return true;
    }

    private void error(String name) {
        String notice = "Trigger the safety barrier! " +
            "Catch not allowed serialize class. " +
            "Class name: " + name + " . " +
            "This means currently maybe being attacking by others." +
            "If you are sure this is a mistake, " +
            "please add this class name to `" + CLASS_DESERIALIZE_ALLOWED_LIST +
            "` as a system environment property.";
        if (counter.incrementAndGet() % 1000 == 0 || counter.get() < 100) {
            logger.error(PROTOCOL_UNSAFE_SERIALIZATION, "", "", notice);
        }
        throw new IllegalArgumentException(notice);
    }

}
