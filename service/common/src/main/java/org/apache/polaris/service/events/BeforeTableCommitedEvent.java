/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.polaris.service.events;

import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * Emitted when Polaris intends to perform a commit to a table. There is no guarantee on the order
 * of this event relative to the validation checks we've performed, which means the commit may still
 * fail Polaris-side validation checks.
 *
 * @param identifier The identifier.
 * @param base The old metadata.
 * @param metadata The new metadata.
 */
public record BeforeTableCommitedEvent(
    TableIdentifier identifier, TableMetadata base, TableMetadata metadata)
    implements PolarisEvent {}
