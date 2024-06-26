/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mariadb.antlr.listener;

import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.antlr.AntlrDdlParser;
import io.debezium.antlr.DataTypeResolver;
import io.debezium.connector.mariadb.antlr.MariaDbAntlrDdlParser;
import io.debezium.ddl.parser.mariadb.generated.MariaDBParser;
import io.debezium.ddl.parser.mariadb.generated.MariaDBParserBaseListener;
import io.debezium.relational.Column;
import io.debezium.relational.ColumnEditor;
import io.debezium.relational.TableEditor;
import io.debezium.relational.ddl.DataType;
import io.debezium.util.Strings;

/**
 * Parser listener for column definitions.
 *
 * @author Chris Cranford
 */
public class ColumnDefinitionParserListener extends MariaDBParserBaseListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ColumnDefinitionParserListener.class);

    private static final Pattern DOT = Pattern.compile("\\.");
    private final MariaDbAntlrDdlParser parser;
    private final DataTypeResolver dataTypeResolver;
    private final TableEditor tableEditor;
    private ColumnEditor columnEditor;
    private boolean uniqueColumn;
    private AtomicReference<Boolean> optionalColumn = new AtomicReference<>();
    private DefaultValueParserListener defaultValueListener;

    private final List<ParseTreeListener> listeners;

    public ColumnDefinitionParserListener(TableEditor tableEditor,
                                          ColumnEditor columnEditor,
                                          MariaDbAntlrDdlParser parser,
                                          List<ParseTreeListener> listeners) {
        this.tableEditor = tableEditor;
        this.columnEditor = columnEditor;
        this.parser = parser;
        this.dataTypeResolver = parser.dataTypeResolver();
        this.listeners = listeners;
    }

    public void setColumnEditor(ColumnEditor columnEditor) {
        this.columnEditor = columnEditor;
    }

    public ColumnEditor getColumnEditor() {
        return columnEditor;
    }

    public Column getColumn() {
        return columnEditor.create();
    }

    @Override
    public void enterColumnDefinition(MariaDBParser.ColumnDefinitionContext ctx) {
        uniqueColumn = false;
        optionalColumn = new AtomicReference<>();
        resolveColumnDataType(ctx.dataType());
        parser.runIfNotNull(() -> {
            defaultValueListener = new DefaultValueParserListener(columnEditor, optionalColumn);
            listeners.add(defaultValueListener);
        }, tableEditor);
        super.enterColumnDefinition(ctx);
    }

    @Override
    public void exitColumnDefinition(MariaDBParser.ColumnDefinitionContext ctx) {
        if (optionalColumn.get() != null) {
            columnEditor.optional(optionalColumn.get().booleanValue());
        }
        if (uniqueColumn && !tableEditor.hasPrimaryKey()) {
            // take the first unique constrain if no primary key is set
            tableEditor.addColumn(columnEditor.create());
            tableEditor.setPrimaryKeyNames(columnEditor.name());
        }
        parser.runIfNotNull(() -> {
            defaultValueListener.exitDefaultValue(false);
            listeners.remove(defaultValueListener);
        }, tableEditor);
        super.exitColumnDefinition(ctx);
    }

    @Override
    public void enterUniqueKeyColumnConstraint(MariaDBParser.UniqueKeyColumnConstraintContext ctx) {
        uniqueColumn = true;
        super.enterUniqueKeyColumnConstraint(ctx);
    }

    @Override
    public void enterPrimaryKeyColumnConstraint(MariaDBParser.PrimaryKeyColumnConstraintContext ctx) {
        // this rule will be parsed only if no primary key is set in a table
        // otherwise the statement can't be executed due to multiple primary key error
        optionalColumn.set(Boolean.FALSE);
        tableEditor.addColumn(columnEditor.create());
        tableEditor.setPrimaryKeyNames(columnEditor.name());
        super.enterPrimaryKeyColumnConstraint(ctx);
    }

    @Override
    public void enterCommentColumnConstraint(MariaDBParser.CommentColumnConstraintContext ctx) {
        if (!parser.skipComments()) {
            if (ctx.STRING_LITERAL() != null) {
                columnEditor.comment(parser.withoutQuotes(ctx.STRING_LITERAL().getText()));
            }
        }
        super.enterCommentColumnConstraint(ctx);
    }

    @Override
    public void enterNullNotnull(MariaDBParser.NullNotnullContext ctx) {
        optionalColumn.set(Boolean.valueOf(ctx.NOT() == null));
        super.enterNullNotnull(ctx);
    }

    @Override
    public void enterAutoIncrementColumnConstraint(MariaDBParser.AutoIncrementColumnConstraintContext ctx) {
        columnEditor.autoIncremented(true);
        columnEditor.generated(true);
        super.enterAutoIncrementColumnConstraint(ctx);
    }

    @Override
    public void enterSerialDefaultColumnConstraint(MariaDBParser.SerialDefaultColumnConstraintContext ctx) {
        serialColumn();
        super.enterSerialDefaultColumnConstraint(ctx);
    }

    private void resolveColumnDataType(MariaDBParser.DataTypeContext dataTypeContext) {
        String charsetName = null;
        DataType dataType = dataTypeResolver.resolveDataType(dataTypeContext);

        if (dataTypeContext instanceof MariaDBParser.StringDataTypeContext) {
            // Same as LongVarcharDataTypeContext but with dimension handling
            MariaDBParser.StringDataTypeContext stringDataTypeContext = (MariaDBParser.StringDataTypeContext) dataTypeContext;

            if (stringDataTypeContext.lengthOneDimension() != null) {
                Integer length = parseLength(stringDataTypeContext.lengthOneDimension().decimalLiteral().getText());
                columnEditor.length(length);
            }

            charsetName = parser.extractCharset(stringDataTypeContext.charsetName(), stringDataTypeContext.collationName());
        }
        else if (dataTypeContext instanceof MariaDBParser.LongVarcharDataTypeContext) {
            // Same as StringDataTypeContext but without dimension handling
            MariaDBParser.LongVarcharDataTypeContext longVarcharTypeContext = (MariaDBParser.LongVarcharDataTypeContext) dataTypeContext;

            charsetName = parser.extractCharset(longVarcharTypeContext.charsetName(), longVarcharTypeContext.collationName());
        }
        else if (dataTypeContext instanceof MariaDBParser.NationalStringDataTypeContext) {
            MariaDBParser.NationalStringDataTypeContext nationalStringDataTypeContext = (MariaDBParser.NationalStringDataTypeContext) dataTypeContext;

            if (nationalStringDataTypeContext.lengthOneDimension() != null) {
                Integer length = parseLength(nationalStringDataTypeContext.lengthOneDimension().decimalLiteral().getText());
                columnEditor.length(length);
            }
        }
        else if (dataTypeContext instanceof MariaDBParser.NationalVaryingStringDataTypeContext) {
            MariaDBParser.NationalVaryingStringDataTypeContext nationalVaryingStringDataTypeContext = (MariaDBParser.NationalVaryingStringDataTypeContext) dataTypeContext;

            if (nationalVaryingStringDataTypeContext.lengthOneDimension() != null) {
                Integer length = parseLength(nationalVaryingStringDataTypeContext.lengthOneDimension().decimalLiteral().getText());
                columnEditor.length(length);
            }
        }
        else if (dataTypeContext instanceof MariaDBParser.DimensionDataTypeContext) {
            MariaDBParser.DimensionDataTypeContext dimensionDataTypeContext = (MariaDBParser.DimensionDataTypeContext) dataTypeContext;

            Integer length = null;
            Integer scale = null;
            if (dimensionDataTypeContext.lengthOneDimension() != null) {
                length = parseLength(dimensionDataTypeContext.lengthOneDimension().decimalLiteral().getText());
            }

            if (dimensionDataTypeContext.lengthTwoDimension() != null) {
                List<MariaDBParser.DecimalLiteralContext> decimalLiterals = dimensionDataTypeContext.lengthTwoDimension().decimalLiteral();
                length = parseLength(decimalLiterals.get(0).getText());
                scale = Integer.valueOf(decimalLiterals.get(1).getText());
            }

            if (dimensionDataTypeContext.lengthTwoOptionalDimension() != null) {
                List<MariaDBParser.DecimalLiteralContext> decimalLiterals = dimensionDataTypeContext.lengthTwoOptionalDimension().decimalLiteral();
                if (decimalLiterals.get(0).REAL_LITERAL() != null) {
                    String[] digits = DOT.split(decimalLiterals.get(0).getText());
                    if (Strings.isNullOrEmpty(digits[0]) || Integer.valueOf(digits[0]) == 0) {
                        // Set default value 10 according mariadb engine
                        length = 10;
                    }
                    else {
                        length = parseLength(digits[0]);
                    }
                }
                else {
                    length = parseLength(decimalLiterals.get(0).getText());
                }

                if (decimalLiterals.size() > 1) {
                    scale = Integer.valueOf(decimalLiterals.get(1).getText());
                }
            }
            if (length != null) {
                columnEditor.length(length);
            }
            if (scale != null) {
                columnEditor.scale(scale);
            }
        }
        else if (dataTypeContext instanceof MariaDBParser.CollectionDataTypeContext) {
            MariaDBParser.CollectionDataTypeContext collectionDataTypeContext = (MariaDBParser.CollectionDataTypeContext) dataTypeContext;
            if (collectionDataTypeContext.charsetName() != null) {
                charsetName = collectionDataTypeContext.charsetName().getText();
            }

            if (dataType.name().equalsIgnoreCase("SET")) {
                // After DBZ-132, it will always be comma separated
                int optionsSize = collectionDataTypeContext.collectionOptions().collectionOption().size();
                columnEditor.length(Math.max(0, optionsSize * 2 - 1)); // number of options + number of commas
            }
            else {
                columnEditor.length(1);
            }
        }

        String dataTypeName = dataType.name().toUpperCase();

        if (dataTypeName.equals("ENUM") || dataTypeName.equals("SET")) {
            // type expression has to be set, because the value converter needs to know the enum or set options
            MariaDBParser.CollectionDataTypeContext collectionDataTypeContext = (MariaDBParser.CollectionDataTypeContext) dataTypeContext;

            List<String> collectionOptions = collectionDataTypeContext.collectionOptions().collectionOption().stream()
                    .map(AntlrDdlParser::getText)
                    .collect(Collectors.toList());

            columnEditor.type(dataTypeName);
            columnEditor.enumValues(collectionOptions);
        }
        else if (dataTypeName.equals("SERIAL")) {
            // SERIAL is an alias for BIGINT UNSIGNED NOT NULL AUTO_INCREMENT UNIQUE
            columnEditor.type("BIGINT UNSIGNED");
            serialColumn();
        }
        else {
            columnEditor.type(dataTypeName);
        }

        int jdbcDataType = dataType.jdbcType();
        columnEditor.jdbcType(jdbcDataType);

        if (columnEditor.length() == -1) {
            columnEditor.length((int) dataType.length());
        }
        if (!columnEditor.scale().isPresent() && dataType.scale() != Column.UNSET_INT_VALUE) {
            columnEditor.scale(dataType.scale());
        }
        if (Types.NCHAR == jdbcDataType || Types.NVARCHAR == jdbcDataType) {
            // NCHAR and NVARCHAR columns always uses utf8 as charset
            columnEditor.charsetName("utf8");

            if (Types.NCHAR == jdbcDataType && columnEditor.length() == -1) {
                // Explicitly set NCHAR column size as 1 when no length specified
                columnEditor.length(1);
            }
        }
        else {
            columnEditor.charsetName(charsetName);
        }
    }

    private Integer parseLength(String lengthStr) {
        Long length = Long.parseLong(lengthStr);
        if (length > Integer.MAX_VALUE) {
            LOGGER.warn("The length '{}' of the column `{}`.`{}` is too large to be supported, truncating it to '{}'",
                    length, tableEditor.tableId(), columnEditor.name(), Integer.MAX_VALUE);
            length = (long) Integer.MAX_VALUE;
        }
        return length.intValue();
    }

    private void serialColumn() {
        if (optionalColumn.get() == null) {
            optionalColumn.set(Boolean.FALSE);
        }
        uniqueColumn = true;
        columnEditor.autoIncremented(true);
        columnEditor.generated(true);
    }
}
