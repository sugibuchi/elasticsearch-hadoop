/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.hadoop.serialization.bulk;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.rest.Resource;
import org.elasticsearch.hadoop.serialization.builder.ValueWriter;
import org.elasticsearch.hadoop.serialization.bulk.TemplatedBulk.FieldWriter;
import org.elasticsearch.hadoop.serialization.field.ConstantFieldExtractor;
import org.elasticsearch.hadoop.serialization.field.FieldExtractor;
import org.elasticsearch.hadoop.serialization.field.IndexExtractor;
import org.elasticsearch.hadoop.serialization.field.JsonFieldExtractors;
import org.elasticsearch.hadoop.util.ObjectUtils;
import org.elasticsearch.hadoop.util.StringUtils;


abstract class AbstractBulkFactory implements BulkFactory {

    private static Log log = LogFactory.getLog(AbstractBulkFactory.class);

    private boolean jsonInput;
    private JsonFieldExtractors jsonExtractors;

    protected Settings settings;
    private ValueWriter<?> valueWriter;
    // used when specifying an index pattern
    private IndexExtractor indexExtractor;
    private FieldExtractor idExtractor, parentExtractor, routingExtractor, versionExtractor, ttlExtractor,
            timestampExtractor, paramsExtractor;

    AbstractBulkFactory(Settings settings) {
        this.settings = settings;
        this.valueWriter = ObjectUtils.instantiate(settings.getSerializerValueWriterClassName(), settings);
        initFieldExtractors(settings);
    }

    private void initFieldExtractors(Settings settings) {
        jsonInput = settings.getInputAsJson();

        if (jsonInput) {
            if (log.isDebugEnabled()) {
                log.debug("JSON input; using internal field extractor for efficient parsing...");
            }

            jsonExtractors = new JsonFieldExtractors(settings);
            indexExtractor = jsonExtractors.indexAndType();

            idExtractor = jsonExtractors.id();
            parentExtractor = jsonExtractors.parent();
            routingExtractor = jsonExtractors.routing();
            versionExtractor = jsonExtractors.version();
            ttlExtractor = jsonExtractors.ttl();
            timestampExtractor = jsonExtractors.timestamp();
            paramsExtractor = jsonExtractors.params();
        }
        else {
            // init extractors (if needed)
            if (settings.getMappingId() != null) {
                settings.setProperty(ConstantFieldExtractor.PROPERTY, settings.getMappingId());
                idExtractor = ObjectUtils.<FieldExtractor> instantiate(settings.getMappingIdExtractorClassName(), settings);
            }
            if (settings.getMappingParent() != null) {
                settings.setProperty(ConstantFieldExtractor.PROPERTY, settings.getMappingParent());
                parentExtractor = ObjectUtils.<FieldExtractor> instantiate(settings.getMappingParentExtractorClassName(), settings);
            }
            if (settings.getMappingRouting() != null) {
                settings.setProperty(ConstantFieldExtractor.PROPERTY, settings.getMappingRouting());
                routingExtractor = ObjectUtils.<FieldExtractor> instantiate(settings.getMappingRoutingExtractorClassName(), settings);
            }
            if (settings.getMappingTtl() != null) {
                settings.setProperty(ConstantFieldExtractor.PROPERTY, settings.getMappingTtl());
                ttlExtractor = ObjectUtils.<FieldExtractor> instantiate(settings.getMappingTtlExtractorClassName(), settings);
            }
            if (settings.getMappingVersion() != null) {
                settings.setProperty(ConstantFieldExtractor.PROPERTY, settings.getMappingVersion());
                versionExtractor = ObjectUtils.<FieldExtractor> instantiate(settings.getMappingVersionExtractorClassName(), settings);
            }
            if (settings.getMappingTimestamp() != null) {
                settings.setProperty(ConstantFieldExtractor.PROPERTY, settings.getMappingTimestamp());
                timestampExtractor = ObjectUtils.<FieldExtractor> instantiate(
                        settings.getMappingTimestampExtractorClassName(), settings);
            }

            // create adapter
            IndexExtractor iformat = ObjectUtils.<IndexExtractor> instantiate(settings.getMappingIndexExtractorClassName(), settings);
            iformat.compile(new Resource(settings, false).toString());

            if (iformat.hasPattern()) {
                indexExtractor = iformat;
            }

            // param extractor
            if (settings.hasUpdateScriptParams()) {
                settings.setProperty(ConstantFieldExtractor.PROPERTY, settings.getUpdateScriptParams());
                paramsExtractor = ObjectUtils.instantiate(settings.getMappingParamsExtractorClassName(), settings);
            }

            if (log.isTraceEnabled()) {
                log.trace(String.format("Instantiated value writer [%s]", valueWriter));
                if (idExtractor != null) {
                    log.trace(String.format("Instantiated id extractor [%s]", idExtractor));
                }
                if (parentExtractor != null) {
                    log.trace(String.format("Instantiated parent extractor [%s]", parentExtractor));
                }
                if (routingExtractor != null) {
                    log.trace(String.format("Instantiated routing extractor [%s]", routingExtractor));
                }
                if (ttlExtractor != null) {
                    log.trace(String.format("Instantiated ttl extractor [%s]", ttlExtractor));
                }
                if (versionExtractor != null) {
                    log.trace(String.format("Instantiated version extractor [%s]", versionExtractor));
                }
                if (timestampExtractor != null) {
                    log.trace(String.format("Instantiated timestamp extractor [%s]", timestampExtractor));
                }
                if (paramsExtractor != null) {
                    log.trace(String.format("Instantiated params extractor [%s]", paramsExtractor));
                }
            }
        }
    }

    protected IndexExtractor index() {
        return indexExtractor;
    }

    protected FieldExtractor id() {
        return idExtractor;
    }

    protected FieldExtractor parent() {
        return parentExtractor;
    }

    protected FieldExtractor routing() {
        return routingExtractor;
    }

    protected FieldExtractor ttl() {
        return ttlExtractor;
    }

    protected FieldExtractor version() {
        return versionExtractor;
    }

    protected FieldExtractor timestamp() {
        return timestampExtractor;
    }

    protected FieldExtractor params() {
        return paramsExtractor;
    }

    @Override
    public BulkCommand createBulk() {
        List<Object> before = new ArrayList<Object>();
        writeBeforeObject(before);

        List<Object> after = new ArrayList<Object>();
        writeAfterObject(after);

        before = compact(before);
        after = compact(after);

        boolean isScriptUpdate = settings.hasUpdateScript();
        // compress pieces
        if (jsonInput) {
            if (isScriptUpdate) {
                return new JsonScriptTemplateBulk(before, after, jsonExtractors, settings);
            }
            return new JsonTemplatedBulk(before, after, jsonExtractors, settings);
        }
        if (isScriptUpdate) {
            return new ScriptTemplateBulk(settings, before, after, valueWriter);
        }
        return new TemplatedBulk(before, after, valueWriter);
    }

    protected void writeAfterObject(List<Object> after) {
        after.add("\n");
    }

    private List<Object> compact(List<Object> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }

        List<Object> compacted = new ArrayList<Object>();
        StringBuilder accumulator = new StringBuilder();
        String lastString = null;
        for (Object object : list) {
            if (object instanceof FieldExtractor) {
                if (accumulator.length() > 0) {
                    compacted.add(accumulator.toString().getBytes(StringUtils.UTF_8));
                    accumulator.setLength(0);
                    lastString = null;
                }
                compacted.add(new FieldWriter((FieldExtractor) object));
            }
            else {
                String str = object.toString();
                if ("\"".equals(lastString) && str.startsWith("\"")) {
                    accumulator.append(",");
                }
                lastString = str;
                accumulator.append(str);
            }
        }

        if (accumulator.length() > 0) {
            compacted.add(accumulator.toString().getBytes(StringUtils.UTF_8));
        }
        return compacted;
    }

    protected void writeBeforeObject(List<Object> pieces) {
        startHeader(pieces);

        index(pieces);

        id(pieces);
        parent(pieces);
        routing(pieces);
        ttl(pieces);
        version(pieces);
        timestamp(pieces);

        otherHeader(pieces);
        endHeader(pieces);

        scriptParams(pieces);
    }

    private void startHeader(List<Object> pieces) {
        pieces.add("{\"" + getOperation() + "\":{");
    }

    private void endHeader(List<Object> pieces) {
        pieces.add("}}\n");
    }

    protected boolean index(List<Object> pieces) {
        if (index() != null) {
            pieces.add(index());
            return true;
        }
        return false;
    }

    protected boolean id(List<Object> pieces) {
        if (id() != null) {
            pieces.add("\"_id\":\"");
            pieces.add(id());
            pieces.add("\"");
            return true;
        }
        return false;
    }

    protected abstract String getOperation();

    protected boolean parent(List<Object> pieces) {
        if (parent() != null) {
            pieces.add("\"_parent\":\"");
            pieces.add(parent());
            pieces.add("\"");
            return true;
        }
        return false;
    }

    protected boolean routing(List<Object> pieces) {
        if (routing() != null) {
            pieces.add("\"_routing\":\"");
            pieces.add(routing());
            pieces.add("\"");
            return true;
        }
        return false;
    }

    protected boolean ttl(List<Object> pieces) {
        if (ttl() != null) {
            pieces.add("\"_ttl\":\"");
            pieces.add(ttl());
            pieces.add("\"");
            return true;
        }
        return false;
    }

    protected boolean version(List<Object> pieces) {
        if (version() != null) {
            pieces.add("\"_version\":\"");
            pieces.add(version());
            pieces.add("\"");
            return true;
        }
        return false;
    }

    protected boolean timestamp(List<Object> pieces) {
        if (timestamp() != null) {
            pieces.add("\"_timestamp\":\"");
            pieces.add(timestamp());
            pieces.add("\"");
            return true;
        }
        return false;
    }

    protected void otherHeader(List<Object> pieces) {
        // no-op
    }

    private boolean scriptParams(List<Object> pieces) {
        // handle json params first
        if (settings.hasUpdateScriptParamsJson()) {
            pieces.add("{\"params\":");
            pieces.add(settings.getUpdateScriptParamsJson().trim());
            pieces.add(",");
            return true;
        }
        if (params() != null) {
            pieces.add("{\"params\":{");
            pieces.add(params());
            pieces.add("},");
            return true;
        }
        return false;
    }
}