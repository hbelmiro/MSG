package com.jfeatures.msg.sql;

import com.jfeatures.msg.codegen.SQLServerDataTypeEnum;
import com.jfeatures.msg.codegen.domain.DBColumn;
import com.jfeatures.msg.codegen.domain.TableColumn;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.select.FromItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SubSelect;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class MsgSqlParser {

    public static Map<TableColumn, ColumnDefinition> dataTypePerColumnWithTableInfo(String sql, Map<String, String> ddlPerTableName) throws JSQLParserException {
        Statement sqlStatement = CCJSqlParserUtil.parse(sql);
        Select selectStatement = (Select) sqlStatement;

        PlainSelect plainSelect = (PlainSelect) selectStatement.getSelectBody();
        ArrayList<String> columnNameList = plainSelect.getSelectItems()
                .stream()
                .map(selectItem -> ((Column) ((SelectExpressionItem) selectItem).getExpression()).getColumnName())
                .collect(Collectors.toCollection(ArrayList::new));

        ArrayList<String> tableAliasList = plainSelect.getSelectItems()
                .stream()
                .map(selectItem -> ((Column) ((SelectExpressionItem) selectItem).getExpression()).getTable() != null
                        ? ((Column) ((SelectExpressionItem) selectItem).getExpression()).getTable().getName()
                        : null)
                .collect(Collectors.toCollection(ArrayList::new));

        ArrayList<String> columnAliasList = plainSelect.getSelectItems()
                .stream()
                .map(selectItem -> ((SelectExpressionItem) selectItem).getAlias()!=null
                        ? ((SelectExpressionItem) selectItem).getAlias().getName()
                        : null)
                .collect(Collectors.toCollection(ArrayList::new));

        Map<TableColumn, ColumnDefinition> resultMap = new HashMap<>();

        for (int i = 0; i < columnNameList.size(); i++) {
            ColumnDefinition columnDefinition;
            String tableName;
            if (isSelectColumnWithOutTableAlias(tableAliasList.get(i))) {
                columnDefinition = ColumnUtils.findColumnByColumnNameWhenTableAliasIsNotAvailable(ddlPerTableName, columnNameList.get(i));
                tableName = TableUtils.findTableNameByColumnName(columnNameList.get(i), ddlPerTableName);
            } else {
                Map<String, String> tableAliasToTableName = getAliasToTableName(plainSelect);
                tableName = TableUtils.getTableName(tableAliasList.get(i), columnNameList.get(i), tableAliasToTableName, ddlPerTableName);
                columnDefinition = ColumnUtils.getColumnDefinition(columnNameList.get(i), ddlPerTableName.get(tableName)).get();
            }
            resultMap.put(new TableColumn(columnNameList.get(i), columnAliasList.get(i), tableName) , columnDefinition);

        }

        return resultMap;
    }

    private static Map<String, String> getAliasToTableName(PlainSelect plainSelect) {
        Map<String, String> tableAliasToTableName = new HashMap<>();
        plainSelect.getFromItem().accept(new FromItemVisitorAdapter() {
            @Override
            public void visit(Table table) {
                String tableName = table.getName();
                log.info("Table columnName: " + tableName);

                Alias tableAlias = table.getAlias();
                if (tableAlias != null) {
                    String aliasName = tableAlias.getName();
                    log.info("Table alias: " + aliasName);
                    tableAliasToTableName.put(aliasName, tableName);
                }
            }
        });

        if(plainSelect.getJoins() == null)
        {
            return tableAliasToTableName;
        }//todo shall we also use join.onExpression in below logic, we are just using getRightItem here
        plainSelect.getJoins().forEach(join -> join.getRightItem().accept(new FromItemVisitorAdapter() {
            @Override
            public void visit(Table table) {
                String tableName = table.getName();
                log.info("Table columnName: " + tableName);

                Alias tableAlias = table.getAlias();
                if (tableAlias != null) {
                    String aliasName = tableAlias.getName();
                    log.info("Table alias: " + aliasName);
                    tableAliasToTableName.put(aliasName, tableName);
                }
            }
        }));
        return tableAliasToTableName;
    }

    private static boolean isSelectColumnWithOutTableAlias(String tableAlias) {
        return tableAlias == null;
    }

    /**
     * Added method extractPredicateHavingLiterals to extract literals from having clause, that is used to create a filter
     * in SQL. Same values should be passed in json request body of controller.
     * @param sql
     * @param ddlPerTableName
     * @return
     * @throws JSQLParserException
     */
    public static List<DBColumn> extractPredicateHavingLiteralsFromWhereClause(String sql, Map<String, String> ddlPerTableName) throws JSQLParserException {
        ArrayList<DBColumn> result = new ArrayList<>();
        Statement sqlStatement = CCJSqlParserUtil.parse(sql);
        Select selectStatement = (Select) sqlStatement;

        PlainSelect plainSelect = (PlainSelect) selectStatement.getSelectBody();
        Map<String, String> tableAliasToTableName = getAliasToTableName(plainSelect);

        Expression whereExpression = ((PlainSelect) selectStatement.getSelectBody()).getWhere();

        //todo handle this null case better, try removing if condition
        if(whereExpression == null) {
            return Collections.emptyList();
        }
        String whereClause = whereExpression.toString();
        log.info("Where condition: " + whereClause);

        Expression expr = CCJSqlParserUtil.parseCondExpression(whereClause);
        expr.accept(new ExpressionVisitorAdapter() {

            @Override
            protected void visitBinaryExpression(BinaryExpression expr) {
                if (expr instanceof ComparisonOperator) {
                    //todo what if left expression or right expression are select expressions(subquery)?
                    if (!(expr.getLeftExpression() instanceof Column) && !expr.getLeftExpression().toString().contains("HARDCODE_AS")) {
                        log.info("left=" + expr.getLeftExpression().toString() + "  op=" + expr.getStringExpression() + "  right=" + expr.getRightExpression());

                        String tableAlias = null;
                        String columnName;
                        if(expr.getRightExpression().toString().contains("'")){
                            tableAlias = StringUtils.substringBefore(expr.getRightExpression().toString(), ".");
                            columnName = StringUtils.substringAfter(expr.getRightExpression().toString(), ".");
                        }
                        else{
                            columnName = expr.getRightExpression().toString();
                        }
                        DBColumn dbColumn = getDbColumn(tableAlias, columnName, tableAliasToTableName, ddlPerTableName);
                        result.add(dbColumn);
                    }
                    if (!(expr.getRightExpression() instanceof Column) && !expr.getRightExpression().toString().contains("HARDCODE_AS")) {
                        log.info("left=" + expr.getLeftExpression() + "  op=" + expr.getStringExpression() + "  right=" + expr.getRightExpression().toString());
                        String tableAlias = null;
                        String columnName;
                        if(expr.getLeftExpression().toString().contains(".")){
                            tableAlias = StringUtils.substringBefore(expr.getLeftExpression().toString(), ".");
                            columnName = StringUtils.substringAfter(expr.getLeftExpression().toString(), ".");
                        }else {
                            columnName = expr.getLeftExpression().toString();
                        }
                        DBColumn dbColumn = getDbColumn(tableAlias, columnName, tableAliasToTableName, ddlPerTableName);
                        result.add(dbColumn);
                    }

                }

                super.visitBinaryExpression(expr); //todo why do we need this? try removing
            }
        });

        return result;
    }

    public static List<DBColumn> extractPredicateHavingLiteralsFromJoinsClause(String sql, Map<String, String> ddlPerTableName) throws JSQLParserException {
        ArrayList<DBColumn> result = new ArrayList<>();
        Statement sqlStatement = CCJSqlParserUtil.parse(sql);
        Select selectStatement = (Select) sqlStatement;

        PlainSelect plainSelect = (PlainSelect) selectStatement.getSelectBody();
        Map<String, String> tableAliasToTableName = getAliasToTableName(plainSelect);

        List<Join> joinList = ((PlainSelect) selectStatement.getSelectBody()).getJoins();

        //todo handle this null case better, try removing if condition
        if(joinList ==null || joinList.isEmpty()) {
            return Collections.emptyList();
        }
        joinList.forEach( joinItem -> log.info(joinItem.toString()));

        joinList.forEach(join ->
        {
            List<DBColumn> dbColumnsFromSubSelectJoinClause;
            List<DBColumn> dbColumnsFromSubSelectFromClause;
            Collection<Expression> onExpressions = join.getOnExpressions();
            if (join.getRightItem() instanceof SubSelect rightItem)
            {
                try
                {
                    dbColumnsFromSubSelectFromClause = extractPredicateHavingLiteralsFromWhereClause(rightItem.getSelectBody().toString(), ddlPerTableName);
                    dbColumnsFromSubSelectJoinClause = extractPredicateHavingLiteralsFromJoinsClause(rightItem.getSelectBody().toString(), ddlPerTableName);
                    result.addAll(dbColumnsFromSubSelectFromClause);
                    result.addAll(dbColumnsFromSubSelectJoinClause);
                } catch (JSQLParserException e)
                {
                    throw new RuntimeException(e);
                }
            }

            if(onExpressions.isEmpty()) {
                return;
            }

            onExpressions.forEach(expression ->
            {
                if (expression instanceof BinaryExpression binaryExpression)
                {

                    List<BinaryExpression> binaryExpressionList = BinaryExpressionUtil.getIndividualBinaryExpression(binaryExpression);
                    binaryExpressionList.forEach( individualBinaryExpression ->
                    {
                        boolean expressionHasLiteral = false;
                        Column column = null;
                        if (!(individualBinaryExpression.getLeftExpression() instanceof Column) && !individualBinaryExpression.getLeftExpression().toString().contains("HARDCODE_AS"))
                        {
                            expressionHasLiteral = true;
                            log.info("left=" + individualBinaryExpression.getLeftExpression().toString() + "  op=" + individualBinaryExpression.getStringExpression() + "  right=" + individualBinaryExpression.getRightExpression());
                            column = (Column) individualBinaryExpression.getRightExpression();
                        }
                        if (!(individualBinaryExpression.getRightExpression() instanceof Column) && !individualBinaryExpression.getRightExpression().toString().contains("HARDCODE_AS"))
                        {
                            expressionHasLiteral = true;
                            log.info("left=" + individualBinaryExpression.getLeftExpression().toString() + "  op=" + individualBinaryExpression.getStringExpression() + "  right=" + individualBinaryExpression.getRightExpression());
                            column = (Column) individualBinaryExpression.getLeftExpression();
                        }

                        if(expressionHasLiteral)
                        {
                            String tableAlias = column.getTable().getName();
                            String columnName = column.getColumnName();
                            DBColumn dbColumn = getDbColumn(tableAlias, columnName, tableAliasToTableName, ddlPerTableName);
                            result.add(dbColumn);
                        }
                    });
                }
            });
        });

        return result;
    }

    /**
     * This method extracts column details used in select clause.
     * todo we are taking tableAlias from table name, need to check if this is correct.
     * @param sql
     * @param ddlPerTableName
     * @return
     * @throws JSQLParserException
     */
    public static Map<TableColumn, DBColumn> getDetailsOfColumnsUsedInSelect(String sql, Map<String, String> ddlPerTableName) throws JSQLParserException {
        Map<TableColumn, DBColumn> result = new HashMap<>();
        Statement sqlStatement = CCJSqlParserUtil.parse(sql);
        Select selectStatement = (Select) sqlStatement;

        PlainSelect plainSelect = (PlainSelect) selectStatement.getSelectBody();
        Map<String, String> tableAliasToTableName = getAliasToTableName(plainSelect);

        plainSelect.getSelectItems().forEach(selectItem -> {
            String columnName = ((Column) ((SelectExpressionItem) selectItem).getExpression()).getColumnName();
            //todo find better way to get table name, we are not getting this in case of column e as it does not have table alias along with column name.
            String tableAlias = ((Column) ((SelectExpressionItem) selectItem).getExpression()).getTable() != null
                    ? ((Column) ((SelectExpressionItem) selectItem).getExpression()).getTable().getName()
                    : null;
            String columnAliasName = ((SelectExpressionItem) selectItem).getAlias()!=null ? ((SelectExpressionItem) selectItem).getAlias().getName() : null;

            DBColumn dbColumn = getDbColumn(tableAlias, columnName, tableAliasToTableName, ddlPerTableName);
            result.put(new TableColumn(dbColumn.columnName(), columnAliasName ,tableAliasToTableName.get(tableAlias)), dbColumn);
        });

        return result;
    }

    private static DBColumn getDbColumn(String tableAlias, String columnName, Map<String, String> tableAliasToTableName, Map<String, String> ddlPerTableName) {
        String tableName = TableUtils.getTableName(tableAlias, columnName, tableAliasToTableName, ddlPerTableName);
        Optional<ColumnDefinition> columnDefinition = ColumnUtils.getColumnDefinition(columnName, ddlPerTableName.get(tableName));
        return new DBColumn(tableName ,columnName, SQLServerDataTypeEnum.getClassForType(columnDefinition.get().getColDataType().getDataType()).getSimpleName(),
                SQLServerDataTypeEnum.getJdbcTypeForDBType(columnDefinition.get().getColDataType().getDataType()));
    }

}
