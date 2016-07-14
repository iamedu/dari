package com.psddev.dari.db.sql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.common.base.Preconditions;
import com.psddev.dari.db.ComparisonPredicate;
import com.psddev.dari.db.CompoundPredicate;
import com.psddev.dari.db.Location;
import com.psddev.dari.db.ObjectField;
import com.psddev.dari.db.ObjectIndex;
import com.psddev.dari.db.Predicate;
import com.psddev.dari.db.PredicateParser;
import com.psddev.dari.db.Query;
import com.psddev.dari.db.Region;
import com.psddev.dari.db.Sorter;
import com.psddev.dari.db.SqlDatabase;
import com.psddev.dari.db.UnsupportedPredicateException;
import com.psddev.dari.db.UnsupportedSorterException;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JoinType;
import org.jooq.RenderContext;
import org.jooq.Select;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;

class SqlQuery {

    public static final String COUNT_ALIAS = "_count";

    protected final AbstractSqlDatabase database;
    protected final Query<?> query;
    protected final String aliasPrefix;

    protected final SqlSchema schema;
    private final DSLContext dslContext;
    private final RenderContext tableRenderContext;
    protected final RenderContext renderContext;

    private final String recordTableAlias;
    private final Table<?> recordTable;
    protected final Field<UUID> recordIdField;
    protected final Field<UUID> recordTypeIdField;
    protected final Map<String, Query.MappedKey> mappedKeys;
    protected final Map<String, ObjectIndex> selectedIndexes;

    private Condition whereCondition;
    private final List<SortField<?>> orderByFields = new ArrayList<>();
    protected final List<SqlQueryJoin> joins = new ArrayList<>();
    private final Map<Query<?>, String> subQueries = new LinkedHashMap<>();
    private final Map<Query<?>, SqlQuery> subSqlQueries = new HashMap<>();

    private boolean needsDistinct;
    protected SqlQueryJoin mysqlIndexHint;
    private boolean forceLeftJoins;

    /**
     * Creates an instance that can translate the given {@code query}
     * with the given {@code database}.
     */
    public SqlQuery(AbstractSqlDatabase database, Query<?> query, String aliasPrefix) {
        this.database = database;
        this.query = query;
        this.aliasPrefix = aliasPrefix;

        schema = database.schema();
        dslContext = DSL.using(database.dialect());
        tableRenderContext = dslContext.renderContext().paramType(ParamType.INLINED).declareTables(true);
        renderContext = dslContext.renderContext().paramType(ParamType.INLINED);

        recordTableAlias = aliasPrefix + "r";
        recordTable = DSL.table(DSL.name(schema.record().getName())).as(recordTableAlias);
        recordIdField = DSL.field(DSL.name(recordTableAlias, schema.recordId().getName()), schema.uuidDataType());
        recordTypeIdField = DSL.field(DSL.name(recordTableAlias, schema.recordTypeId().getName()), schema.uuidDataType());
        mappedKeys = query.mapEmbeddedKeys(database.getEnvironment());
        selectedIndexes = new HashMap<>();

        for (Map.Entry<String, Query.MappedKey> entry : mappedKeys.entrySet()) {
            selectIndex(entry.getKey(), entry.getValue());
        }
    }

    private void selectIndex(String queryKey, Query.MappedKey mappedKey) {
        ObjectIndex selectedIndex = null;
        int maxMatchCount = 0;

        for (ObjectIndex index : mappedKey.getIndexes()) {
            List<String> indexFields = index.getFields();
            int matchCount = 0;

            for (Query.MappedKey mk : mappedKeys.values()) {
                ObjectField mkf = mk.getField();
                if (mkf != null && indexFields.contains(mkf.getInternalName())) {
                    ++ matchCount;
                }
            }

            if (matchCount > maxMatchCount) {
                selectedIndex = index;
                maxMatchCount = matchCount;
            }
        }

        if (selectedIndex != null) {
            if (maxMatchCount == 1) {
                for (ObjectIndex index : mappedKey.getIndexes()) {
                    if (index.getFields().size() == 1) {
                        selectedIndex = index;
                        break;
                    }
                }
            }

            selectedIndexes.put(queryKey, selectedIndex);
        }
    }

    public SqlQuery(AbstractSqlDatabase database, Query<?> query) {
        this(database, query, "");
    }

    protected Field<Object> aliasedField(String alias, String field) {
        return field != null ? DSL.field(DSL.name(aliasPrefix + alias, field)) : null;
    }

    private SqlQuery getOrCreateSubSqlQuery(Query<?> subQuery, boolean forceLeftJoins) {
        SqlQuery subSqlQuery = subSqlQueries.get(subQuery);

        if (subSqlQuery == null) {
            subSqlQuery = new SqlQuery(database, subQuery, aliasPrefix + "s" + subSqlQueries.size());
            subSqlQuery.forceLeftJoins = forceLeftJoins;

            subSqlQueries.put(subQuery, subSqlQuery);
        }

        return subSqlQuery;
    }

    private Table<?> initialize(Table<?> table) {

        // Build the WHERE clause.
        whereCondition = query.isFromAll()
                ? DSL.trueCondition()
                : recordTypeIdField.in(query.getConcreteTypeIds(database));

        Predicate predicate = query.getPredicate();

        if (predicate != null) {
            Condition condition = createWhereCondition(predicate, null, false);

            if (condition != null) {
                whereCondition = whereCondition.and(condition);
            }
        }

        // Creates jOOQ SortField from Dari Sorter.
        for (Sorter sorter : query.getSorters()) {
            SqlQuerySorter sqlQuerySorter = SqlQuerySorter.find(sorter.getOperator());

            if (sqlQuerySorter == null) {
                throw new UnsupportedSorterException(database, sorter);
            }

            String queryKey = (String) sorter.getOptions().get(0);
            SqlQueryJoin join = SqlQueryJoin.findOrCreate(this, queryKey);

            join.useLeftOuter();

            Query<?> subQuery = mappedKeys.get(queryKey).getSubQueryWithSorter(sorter, 0);

            if (subQuery != null) {
                SqlQuery subSqlQuery = getOrCreateSubSqlQuery(subQuery, true);

                subQueries.put(subQuery, renderContext.render(join.valueField) + " = ");
                orderByFields.addAll(subSqlQuery.orderByFields);

            } else {
                orderByFields.add(
                        sqlQuerySorter.createSortField(
                                schema,
                                join,
                                sorter.getOptions()));
            }
        }

        // Join all index tables used so far.
        for (SqlQueryJoin join : joins) {
            if (!join.symbolIds.isEmpty()) {
                table = table.join(join.table, forceLeftJoins || join.isLeftOuter() ? JoinType.LEFT_OUTER_JOIN : JoinType.JOIN)
                        .on(join.idField.eq(recordIdField))
                        .and(join.typeIdField.eq(recordTypeIdField))
                        .and(join.symbolIdField.in(join.symbolIds));
            }
        }

        // Join all index tables used in sub-queries.
        for (Map.Entry<Query<?>, String> entry : subQueries.entrySet()) {
            Query<?> subQuery = entry.getKey();
            SqlQuery subSqlQuery = getOrCreateSubSqlQuery(subQuery, false);
            String alias = subSqlQuery.recordTableAlias;

            table = subSqlQuery.initialize(
                    table.join(DSL.table(DSL.name(schema.record().getName())).as(alias))
                            .on(entry.getValue() + renderContext.render(DSL.field(DSL.name(alias, schema.recordId().getName())))));

            whereCondition = whereCondition.and(subSqlQuery.whereCondition);

            if (subSqlQuery.needsDistinct) {
                needsDistinct = true;
            }
        }

        return table;
    }

    // Creates jOOQ Condition from Dari Predicate.
    private Condition createWhereCondition(
            Predicate predicate,
            Predicate parentPredicate,
            boolean usesLeftJoin) {

        if (predicate instanceof CompoundPredicate) {
            CompoundPredicate compoundPredicate = (CompoundPredicate) predicate;
            String operator = compoundPredicate.getOperator();
            boolean isNot = PredicateParser.NOT_OPERATOR.equals(operator);

            // e.g. (child1) OR (child2) OR ... (child#)
            if (isNot || PredicateParser.OR_OPERATOR.equals(operator)) {
                List<Predicate> children = compoundPredicate.getChildren();
                boolean usesLeftJoinChildren;

                if (children.size() > 1) {
                    usesLeftJoinChildren = true;
                    needsDistinct = true;

                } else {
                    usesLeftJoinChildren = isNot;
                }

                Condition compoundCondition = null;

                for (Predicate child : children) {
                    Condition childCondition = createWhereCondition(child, predicate, usesLeftJoinChildren);

                    if (childCondition != null) {
                        compoundCondition = compoundCondition != null
                                ? compoundCondition.or(childCondition)
                                : childCondition;
                    }
                }

                return isNot && compoundCondition != null
                        ? compoundCondition.not()
                        : compoundCondition;

            // e.g. (child1) AND (child2) AND .... (child#)
            } else if (PredicateParser.AND_OPERATOR.equals(operator)) {
                Condition compoundCondition = null;

                for (Predicate child : compoundPredicate.getChildren()) {
                    Condition childCondition = createWhereCondition(child, predicate, usesLeftJoin);

                    if (childCondition != null) {
                        compoundCondition = compoundCondition != null
                                ? compoundCondition.and(childCondition)
                                : childCondition;
                    }
                }

                return compoundCondition;
            }

        } else if (predicate instanceof ComparisonPredicate) {
            ComparisonPredicate comparisonPredicate = (ComparisonPredicate) predicate;
            String queryKey = comparisonPredicate.getKey();
            Query.MappedKey mappedKey = mappedKeys.get(queryKey);
            boolean isFieldCollection = mappedKey.isInternalCollectionType();
            SqlQueryJoin join = null;

            if (mappedKey.getField() != null
                    && parentPredicate instanceof CompoundPredicate
                    && PredicateParser.OR_OPERATOR.equals(parentPredicate.getOperator())) {

                for (SqlQueryJoin j : joins) {
                    if (j.parent == parentPredicate
                            && j.sqlIndex.equals(schema.findSelectIndexTable(mappedKeys.get(queryKey).getInternalType()))) {

                        needsDistinct = true;
                        join = j;

                        join.addSymbolId(queryKey);
                        break;
                    }
                }

                if (join == null) {
                    join = SqlQueryJoin.findOrCreate(this, queryKey);
                    join.parent = parentPredicate;
                }

            } else if (isFieldCollection) {
                join = SqlQueryJoin.create(this, queryKey);

            } else {
                join = SqlQueryJoin.findOrCreate(this, queryKey);
            }

            if (usesLeftJoin) {
                join.useLeftOuter();
            }

            if (isFieldCollection && join.sqlIndex == null) {
                needsDistinct = true;
            }

            Query<?> valueQuery = mappedKey.getSubQueryWithComparison(comparisonPredicate);
            String operator = comparisonPredicate.getOperator();
            boolean isNotEqualsAll = PredicateParser.NOT_EQUALS_ALL_OPERATOR.equals(operator);

            // e.g. field IN (SELECT ...)
            if (valueQuery != null) {
                if (isNotEqualsAll || isFieldCollection) {
                    needsDistinct = true;
                }

                if (findSimilarComparison(mappedKey.getField(), query.getPredicate())) {
                    Table<?> subQueryTable = DSL.table(new SqlQuery(database, valueQuery).subQueryStatement());

                    return isNotEqualsAll
                            ? join.valueField.notIn(subQueryTable)
                            : join.valueField.in(subQueryTable);

                } else {
                    SqlQuery subSqlQuery = getOrCreateSubSqlQuery(valueQuery, join.isLeftOuter());
                    subQueries.put(valueQuery, renderContext.render(join.valueField) + (isNotEqualsAll ? " != " : " = "));
                    return subSqlQuery.whereCondition;
                }
            }

            List<Condition> comparisonConditions = new ArrayList<>();
            boolean hasMissing = false;

            if (isNotEqualsAll || PredicateParser.EQUALS_ANY_OPERATOR.equals(operator)) {
                List<Object> inValues = new ArrayList<>();

                for (Object value : comparisonPredicate.resolveValues(database)) {
                    if (value == null) {
                        comparisonConditions.add(DSL.falseCondition());

                    } else if (value == Query.MISSING_VALUE) {
                        hasMissing = true;

                        if (isNotEqualsAll) {
                            if (isFieldCollection) {
                                needsDistinct = true;
                            }

                            comparisonConditions.add(join.valueField.isNotNull());

                        } else {
                            join.useLeftOuter();
                            comparisonConditions.add(join.valueField.isNull());
                        }

                    } else if (value instanceof Region) {
                        if (!database.isIndexSpatial()) {
                            throw new UnsupportedOperationException();
                        }

                        if (!(join.sqlIndex instanceof LocationSqlIndex)) {
                            throw new UnsupportedOperationException();
                        }

                        Condition contains = schema.stContains(
                                schema.stGeomFromText(DSL.inline(((Region) value).toPolygonWkt())),
                                join.valueField);

                        comparisonConditions.add(isNotEqualsAll
                                ? contains.not()
                                : contains);

                    } else {
                        if (!database.isIndexSpatial() && value instanceof Location) {
                            throw new UnsupportedOperationException();
                        }

                        if (isNotEqualsAll) {
                            needsDistinct = true;
                            hasMissing = true;

                            join.useLeftOuter();
                            comparisonConditions.add(
                                    join.valueField.isNull().or(
                                            join.valueField.ne(join.value(value))));

                        } else {
                            inValues.add(join.value(value));
                        }
                    }
                }

                if (!inValues.isEmpty()) {
                    comparisonConditions.add(join.valueField.in(inValues));
                }

            } else {
                SqlQueryComparison sqlQueryComparison = SqlQueryComparison.find(operator);

                // e.g. field OP value1 OR field OP value2 OR ... field OP value#
                if (sqlQueryComparison != null) {
                    for (Object value : comparisonPredicate.resolveValues(database)) {
                        if (value == null) {
                            comparisonConditions.add(DSL.falseCondition());

                        } else if (value instanceof Location) {
                            if (!database.isIndexSpatial()) {
                                throw new UnsupportedOperationException();
                            }

                            if (!(join.sqlIndex instanceof RegionSqlIndex)) {
                                throw new UnsupportedOperationException();
                            }

                            comparisonConditions.add(
                                    schema.stContains(
                                            join.valueField,
                                            schema.stGeomFromText(DSL.inline(((Location) value).toWkt()))));

                        } else if (value == Query.MISSING_VALUE) {
                            hasMissing = true;

                            join.useLeftOuter();
                            comparisonConditions.add(join.valueField.isNull());

                        } else {
                            comparisonConditions.add(
                                    sqlQueryComparison.createCondition(
                                            join.valueField,
                                            join.value(value)));
                        }
                    }
                }
            }

            if (comparisonConditions.isEmpty()) {
                return isNotEqualsAll ? DSL.trueCondition() : DSL.falseCondition();
            }

            Condition whereCondition = isNotEqualsAll
                    ? DSL.and(comparisonConditions)
                    : DSL.or(comparisonConditions);

            if (!hasMissing) {
                if (join.needsIndexTable) {
                    String indexKey = mappedKeys.get(queryKey).getIndexKey(selectedIndexes.get(queryKey));

                    if (indexKey != null) {
                        whereCondition = join.symbolIdField.eq(database.getSymbolId(indexKey)).and(whereCondition);
                    }
                }

                if (join.needsIsNotNull) {
                    whereCondition = join.valueField.isNotNull().and(whereCondition);
                }

                if (comparisonConditions.size() > 1) {
                    needsDistinct = true;
                }
            }

            return whereCondition;
        }

        throw new UnsupportedPredicateException(this, predicate);
    }

    private boolean findSimilarComparison(ObjectField field, Predicate predicate) {
        if (field != null) {
            if (predicate instanceof CompoundPredicate) {
                for (Predicate child : ((CompoundPredicate) predicate).getChildren()) {
                    if (findSimilarComparison(field, child)) {
                        return true;
                    }
                }

            } else if (predicate instanceof ComparisonPredicate) {
                ComparisonPredicate comparison = (ComparisonPredicate) predicate;
                Query.MappedKey mappedKey = mappedKeys.get(comparison.getKey());

                if (field.equals(mappedKey.getField())
                        && mappedKey.getSubQueryWithComparison(comparison) == null) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Returns an SQL statement that can be used to get a count
     * of all rows matching the query.
     */
    public String countStatement() {
        Table<?> table = initialize(recordTable);

        return tableRenderContext.render(dslContext
                .select(needsDistinct ? recordIdField.countDistinct() : recordIdField.count())
                .from(table)
                .where(whereCondition));
    }

    /**
     * Returns an SQL statement that can be used to delete all rows
     * matching the query.
     */
    public String deleteStatement() {
        Table<?> table = initialize(recordTable);

        return tableRenderContext.render(dslContext
                .deleteFrom(table)
                .where(whereCondition));
    }

    /**
     * Returns an SQL statement that can be used to group rows by the values
     * of the given {@code groupKeys}.
     *
     * @param groupKeys Can't be {@code null} or empty.
     * @throws IllegalArgumentException If {@code groupKeys} is empty.
     * @throws NullPointerException If {@code groupKeys} is {@code null}.
     */
    public String groupStatement(String... groupKeys) {
        Preconditions.checkNotNull(groupKeys, "[groupKeys] can't be null!");
        Preconditions.checkArgument(groupKeys.length > 0, "[groupKeys] can't be empty!");

        List<Field<?>> groupByFields = new ArrayList<>();

        for (String groupKey : groupKeys) {
            Query.MappedKey mappedKey = query.mapEmbeddedKey(database.getEnvironment(), groupKey);

            mappedKeys.put(groupKey, mappedKey);
            selectIndex(groupKey, mappedKey);

            SqlQueryJoin join = SqlQueryJoin.findOrCreate(this, groupKey);
            Query<?> subQuery = mappedKey.getSubQueryWithGroupBy();

            if (subQuery == null) {
                groupByFields.add(join.valueField);

            } else {
                SqlQuery subSqlQuery = getOrCreateSubSqlQuery(subQuery, true);

                subQueries.put(subQuery, renderContext.render(join.valueField) + " = ");
                subSqlQuery.joins.forEach(j -> groupByFields.add(j.valueField));
            }
        }

        Table<?> table = initialize(recordTable);
        List<Field<?>> selectFields = new ArrayList<>();

        selectFields.add((needsDistinct
                ? recordIdField.countDistinct()
                : recordIdField.count())
                .as(COUNT_ALIAS));

        selectFields.addAll(groupByFields);

        return tableRenderContext.render(dslContext
                .select(selectFields)
                .from(table)
                .where(whereCondition)
                .groupBy(groupByFields)
                .orderBy(orderByFields));
    }

    /**
     * Returns an SQL statement that can be used to get when the rows
     * matching the query were last updated.
     */
    public String lastUpdateStatement() {
        Table<?> table = initialize(DSL.table(schema.recordUpdate().getName()).as(recordTableAlias));

        return tableRenderContext.render(dslContext
                .select(DSL.field(DSL.name(recordTableAlias, schema.recordUpdateDate().getName())).max())
                .from(table)
                .where(whereCondition));
    }

    /**
     * Returns an SQL statement that can be used to list all rows
     * matching the query.
     */
    public String selectStatement() {
        Table<?> table = initialize(recordTable);
        List<Field<?>> selectFields = new ArrayList<>();

        selectFields.add(recordIdField);
        selectFields.add(recordTypeIdField);

        List<String> queryFields = query.getFields();

        if (queryFields == null) {
            selectFields.add(DSL.field(DSL.name(recordTableAlias, SqlDatabase.DATA_COLUMN)));

        } else if (!queryFields.isEmpty()) {
            queryFields.forEach(queryField -> selectFields.add(DSL.field(DSL.name(queryField))));
        }

        Select<?> select = (needsDistinct
                ? dslContext.selectDistinct(recordIdField, recordTypeIdField)
                : dslContext.select(selectFields))
                .from(table)
                .where(whereCondition)
                .orderBy(orderByFields);

        if (needsDistinct && selectFields.size() > 2) {
            String distinctAlias = aliasPrefix + "d";
            select = dslContext
                    .select(selectFields)
                    .from(recordTable)
                    .join(select.asTable().as(distinctAlias))
                    .on(recordTypeIdField.eq(DSL.field(DSL.name(distinctAlias, SqlDatabase.TYPE_ID_COLUMN), schema.uuidDataType())))
                    .and(recordIdField.eq(DSL.field(DSL.name(distinctAlias, SqlDatabase.ID_COLUMN), schema.uuidDataType())));
        }

        return tableRenderContext.render(select);
    }

    /**
     * Returns an SQL statement that can be used as a sub-query.
     */
    public String subQueryStatement() {
        Table<?> table = initialize(recordTable);

        return tableRenderContext.render((needsDistinct
                ? dslContext.selectDistinct(recordIdField)
                : dslContext.select(recordIdField))
                .from(table)
                .where(whereCondition)
                .orderBy(orderByFields));
    }
}
