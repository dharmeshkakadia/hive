/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.optimizer.optiq.translator;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.FunctionInfo;
import org.apache.hadoop.hive.ql.exec.FunctionRegistry;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.optimizer.optiq.OptiqSemanticException;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.apache.hadoop.hive.ql.parse.ParseDriver;
import org.apache.hadoop.hive.ql.udf.SettableUDF;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFBridge;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPNegative;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPPositive;
import org.apache.hadoop.hive.serde2.typeinfo.CharTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.DecimalTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory;
import org.apache.hadoop.hive.serde2.typeinfo.VarcharTypeInfo;
import org.eigenbase.reltype.RelDataType;
import org.eigenbase.reltype.RelDataTypeFactory;
import org.eigenbase.sql.SqlAggFunction;
import org.eigenbase.sql.SqlFunction;
import org.eigenbase.sql.SqlFunctionCategory;
import org.eigenbase.sql.SqlKind;
import org.eigenbase.sql.SqlOperator;
import org.eigenbase.sql.fun.SqlStdOperatorTable;
import org.eigenbase.sql.type.InferTypes;
import org.eigenbase.sql.type.OperandTypes;
import org.eigenbase.sql.type.ReturnTypes;
import org.eigenbase.sql.type.SqlOperandTypeChecker;
import org.eigenbase.sql.type.SqlOperandTypeInference;
import org.eigenbase.sql.type.SqlReturnTypeInference;
import org.eigenbase.sql.type.SqlTypeFamily;
import org.eigenbase.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

public class SqlFunctionConverter {
  private static final Log LOG = LogFactory.getLog(SqlFunctionConverter.class);

  static final Map<String, SqlOperator>    hiveToOptiq;
  static final Map<SqlOperator, HiveToken> optiqToHiveToken;
  static final Map<SqlOperator, String>    reverseOperatorMap;

  static {
    StaticBlockBuilder builder = new StaticBlockBuilder();
    hiveToOptiq = ImmutableMap.copyOf(builder.hiveToOptiq);
    optiqToHiveToken = ImmutableMap.copyOf(builder.optiqToHiveToken);
    reverseOperatorMap = ImmutableMap.copyOf(builder.reverseOperatorMap);
  }

  public static SqlOperator getOptiqOperator(String funcTextName, GenericUDF hiveUDF,
      ImmutableList<RelDataType> optiqArgTypes, RelDataType retType) throws OptiqSemanticException {
    // handle overloaded methods first
    if (hiveUDF instanceof GenericUDFOPNegative) {
      return SqlStdOperatorTable.UNARY_MINUS;
    } else if (hiveUDF instanceof GenericUDFOPPositive) {
      return SqlStdOperatorTable.UNARY_PLUS;
    } // do generic lookup
    String name = null;
    if (StringUtils.isEmpty(funcTextName)) {
      name = getName(hiveUDF); // this should probably never happen, see getName comment
      LOG.warn("The function text was empty, name from annotation is " + name);
    } else {
      // We could just do toLowerCase here and let SA qualify it, but let's be proper...
      name = FunctionRegistry.getNormalizedFunctionName(funcTextName);
    }
    return getOptiqFn(name, optiqArgTypes, retType);
  }

  public static GenericUDF getHiveUDF(SqlOperator op, RelDataType dt) {
    String name = reverseOperatorMap.get(op);
    if (name == null)
      name = op.getName();
    FunctionInfo hFn = name != null ? FunctionRegistry.getFunctionInfo(name) : null;
    if (hFn == null)
      hFn = handleExplicitCast(op, dt);
    return hFn == null ? null : hFn.getGenericUDF();
  }

  private static FunctionInfo handleExplicitCast(SqlOperator op, RelDataType dt) {
    FunctionInfo castUDF = null;

    if (op.kind == SqlKind.CAST) {
      TypeInfo castType = TypeConverter.convert(dt);

      if (castType.equals(TypeInfoFactory.byteTypeInfo)) {
        castUDF = FunctionRegistry.getFunctionInfo("tinyint");
      } else if (castType instanceof CharTypeInfo) {
        castUDF = handleCastForParameterizedType(castType,
          FunctionRegistry.getFunctionInfo("char"));
      } else if (castType instanceof VarcharTypeInfo) {
        castUDF = handleCastForParameterizedType(castType,
          FunctionRegistry.getFunctionInfo("varchar"));
      } else if (castType.equals(TypeInfoFactory.stringTypeInfo)) {
        castUDF = FunctionRegistry.getFunctionInfo("string");
      } else if (castType.equals(TypeInfoFactory.booleanTypeInfo)) {
        castUDF = FunctionRegistry.getFunctionInfo("boolean");
      } else if (castType.equals(TypeInfoFactory.shortTypeInfo)) {
        castUDF = FunctionRegistry.getFunctionInfo("smallint");
      } else if (castType.equals(TypeInfoFactory.intTypeInfo)) {
        castUDF = FunctionRegistry.getFunctionInfo("int");
      } else if (castType.equals(TypeInfoFactory.longTypeInfo)) {
        castUDF = FunctionRegistry.getFunctionInfo("bigint");
      } else if (castType.equals(TypeInfoFactory.floatTypeInfo)) {
        castUDF = FunctionRegistry.getFunctionInfo("float");
      } else if (castType.equals(TypeInfoFactory.doubleTypeInfo)) {
        castUDF = FunctionRegistry.getFunctionInfo("double");
      } else if (castType.equals(TypeInfoFactory.timestampTypeInfo)) {
        castUDF = FunctionRegistry.getFunctionInfo("timestamp");
      } else if (castType.equals(TypeInfoFactory.dateTypeInfo)) {
        castUDF = FunctionRegistry.getFunctionInfo("datetime");
      } else if (castType instanceof DecimalTypeInfo) {
        castUDF = handleCastForParameterizedType(castType,
          FunctionRegistry.getFunctionInfo("decimal"));
      } else if (castType.equals(TypeInfoFactory.binaryTypeInfo)) {
        castUDF = FunctionRegistry.getFunctionInfo("binary");
      } else throw new IllegalStateException("Unexpected type : " +
        castType.getQualifiedName());
    }

    return castUDF;
  }

  private static FunctionInfo handleCastForParameterizedType(TypeInfo ti, FunctionInfo fi) {
    SettableUDF udf = (SettableUDF)fi.getGenericUDF();
    try {
      udf.setTypeInfo(ti);
    } catch (UDFArgumentException e) {
      throw new RuntimeException(e);
    }
    return new FunctionInfo(fi.isNative(),fi.getDisplayName(),(GenericUDF)udf);
  }

  // TODO: 1) handle Agg Func Name translation 2) is it correct to add func args
  // as child of func?
  public static ASTNode buildAST(SqlOperator op, List<ASTNode> children) {
    HiveToken hToken = optiqToHiveToken.get(op);
    ASTNode node;
    if (hToken != null) {
      node = (ASTNode) ParseDriver.adaptor.create(hToken.type, hToken.text);
    } else {
      node = (ASTNode) ParseDriver.adaptor.create(HiveParser.TOK_FUNCTION, "TOK_FUNCTION");
      if (op.kind != SqlKind.CAST) {
        if (op.kind == SqlKind.MINUS_PREFIX) {
          node = (ASTNode) ParseDriver.adaptor.create(HiveParser.MINUS, "MINUS");
        } else if (op.kind == SqlKind.PLUS_PREFIX) {
          node = (ASTNode) ParseDriver.adaptor.create(HiveParser.PLUS, "PLUS");
        } else {
          if (op.getName().toUpperCase()
              .equals(SqlStdOperatorTable.COUNT.getName())
              && children.size() == 0) {
            node = (ASTNode) ParseDriver.adaptor.create(
                HiveParser.TOK_FUNCTIONSTAR, "TOK_FUNCTIONSTAR");
          }
          node.addChild((ASTNode) ParseDriver.adaptor.create(HiveParser.Identifier, op.getName()));
        }
      }
    }

    for (ASTNode c : children) {
      ParseDriver.adaptor.addChild(node, c);
    }
    return node;
  }

  /**
   * Build AST for flattened Associative expressions ('and', 'or'). Flattened
   * expressions is of the form or[x,y,z] which is originally represented as
   * "or[x, or[y, z]]".
   */
  public static ASTNode buildAST(SqlOperator op, List<ASTNode> children, int i) {
    if (i + 1 < children.size()) {
      HiveToken hToken = optiqToHiveToken.get(op);
      ASTNode curNode = ((ASTNode) ParseDriver.adaptor.create(hToken.type, hToken.text));
      ParseDriver.adaptor.addChild(curNode, children.get(i));
      ParseDriver.adaptor.addChild(curNode, buildAST(op, children, i + 1));
      return curNode;
    } else {
      return children.get(i);
    }

  }

  // TODO: this is not valid. Function names for built-in UDFs are specified in FunctionRegistry,
  //       and only happen to match annotations. For user UDFs, the name is what user specifies at
  //       creation time (annotation can be absent, different, or duplicate some other function).
  private static String getName(GenericUDF hiveUDF) {
    String udfName = null;
    if (hiveUDF instanceof GenericUDFBridge) {
      udfName = ((GenericUDFBridge) hiveUDF).getUdfName();
    } else {
      Class<? extends GenericUDF> udfClass = hiveUDF.getClass();
      Annotation udfAnnotation = udfClass.getAnnotation(Description.class);

      if (udfAnnotation != null && udfAnnotation instanceof Description) {
        Description udfDescription = (Description) udfAnnotation;
        udfName = udfDescription.name();
        if (udfName != null) {
          String[] aliases = udfName.split(",");
          if (aliases.length > 0)
            udfName = aliases[0];
        }
      }

      if (udfName == null || udfName.isEmpty()) {
        udfName = hiveUDF.getClass().getName();
        int indx = udfName.lastIndexOf(".");
        if (indx >= 0) {
          indx += 1;
          udfName = udfName.substring(indx);
        }
      }
    }

    return udfName;
  }

  /** This class is used to build immutable hashmaps in the static block above. */
  private static class StaticBlockBuilder {
    final Map<String, SqlOperator>    hiveToOptiq        = Maps.newHashMap();
    final Map<SqlOperator, HiveToken> optiqToHiveToken   = Maps.newHashMap();
    final Map<SqlOperator, String>    reverseOperatorMap = Maps.newHashMap();

    StaticBlockBuilder() {
      registerFunction("+", SqlStdOperatorTable.PLUS, hToken(HiveParser.PLUS, "+"));
      registerFunction("-", SqlStdOperatorTable.MINUS, hToken(HiveParser.MINUS, "-"));
      registerFunction("*", SqlStdOperatorTable.MULTIPLY, hToken(HiveParser.STAR, "*"));
      registerFunction("/", SqlStdOperatorTable.DIVIDE, hToken(HiveParser.STAR, "/"));
      registerFunction("%", SqlStdOperatorTable.MOD, hToken(HiveParser.STAR, "%"));
      registerFunction("and", SqlStdOperatorTable.AND, hToken(HiveParser.KW_AND, "and"));
      registerFunction("or", SqlStdOperatorTable.OR, hToken(HiveParser.KW_OR, "or"));
      registerFunction("=", SqlStdOperatorTable.EQUALS, hToken(HiveParser.EQUAL, "="));
      registerFunction("<", SqlStdOperatorTable.LESS_THAN, hToken(HiveParser.LESSTHAN, "<"));
      registerFunction("<=", SqlStdOperatorTable.LESS_THAN_OR_EQUAL,
          hToken(HiveParser.LESSTHANOREQUALTO, "<="));
      registerFunction(">", SqlStdOperatorTable.GREATER_THAN, hToken(HiveParser.GREATERTHAN, ">"));
      registerFunction(">=", SqlStdOperatorTable.GREATER_THAN_OR_EQUAL,
          hToken(HiveParser.GREATERTHANOREQUALTO, ">="));
      registerFunction("!", SqlStdOperatorTable.NOT, hToken(HiveParser.KW_NOT, "not"));
    }

    private void registerFunction(String name, SqlOperator optiqFn, HiveToken hiveToken) {
      reverseOperatorMap.put(optiqFn, name);
      FunctionInfo hFn = FunctionRegistry.getFunctionInfo(name);
      if (hFn != null) {
        String hFnName = getName(hFn.getGenericUDF());
        hiveToOptiq.put(hFnName, optiqFn);

        if (hiveToken != null) {
          optiqToHiveToken.put(optiqFn, hiveToken);
        }
      }
    }
  }

  private static HiveToken hToken(int type, String text) {
    return new HiveToken(type, text);
  }

  public static class OptiqUDAF extends SqlAggFunction {
    final ImmutableList<RelDataType> m_argTypes;
    final RelDataType                m_retType;

    public OptiqUDAF(String opName, SqlReturnTypeInference returnTypeInference,
        SqlOperandTypeInference operandTypeInference, SqlOperandTypeChecker operandTypeChecker,
        ImmutableList<RelDataType> argTypes, RelDataType retType) {
      super(opName, SqlKind.OTHER_FUNCTION, returnTypeInference, operandTypeInference,
          operandTypeChecker, SqlFunctionCategory.USER_DEFINED_FUNCTION);
      m_argTypes = argTypes;
      m_retType = retType;
    }

    @Override
    public List<RelDataType> getParameterTypes(final RelDataTypeFactory typeFactory) {
      return m_argTypes;
    }

    @Override
    public RelDataType getReturnType(final RelDataTypeFactory typeFactory) {
      return m_retType;
    }
  }

  private static class OptiqUDFInfo {
    private String                     m_udfName;
    private SqlReturnTypeInference     m_returnTypeInference;
    private SqlOperandTypeInference    m_operandTypeInference;
    private SqlOperandTypeChecker      m_operandTypeChecker;
    private ImmutableList<RelDataType> m_argTypes;
    private RelDataType                m_retType;
  }

  private static OptiqUDFInfo getUDFInfo(String hiveUdfName,
      ImmutableList<RelDataType> optiqArgTypes, RelDataType optiqRetType) {
    OptiqUDFInfo udfInfo = new OptiqUDFInfo();
    udfInfo.m_udfName = hiveUdfName;
    udfInfo.m_returnTypeInference = ReturnTypes.explicit(optiqRetType);
    udfInfo.m_operandTypeInference = InferTypes.explicit(optiqArgTypes);
    ImmutableList.Builder<SqlTypeFamily> typeFamilyBuilder = new ImmutableList.Builder<SqlTypeFamily>();
    for (RelDataType at : optiqArgTypes) {
      typeFamilyBuilder.add(Util.first(at.getSqlTypeName().getFamily(), SqlTypeFamily.ANY));
    }
    udfInfo.m_operandTypeChecker = OperandTypes.family(typeFamilyBuilder.build());

    udfInfo.m_argTypes = ImmutableList.<RelDataType> copyOf(optiqArgTypes);
    udfInfo.m_retType = optiqRetType;

    return udfInfo;
  }

  public static SqlOperator getOptiqFn(String hiveUdfName,
      ImmutableList<RelDataType> optiqArgTypes, RelDataType optiqRetType) throws OptiqSemanticException{

    if (hiveUdfName != null && hiveUdfName.trim().equals("<=>")) {
      // We can create Optiq IS_DISTINCT_FROM operator for this. But since our
      // join reordering algo cant handle this anyway there is no advantage of this.
      // So, bail out for now.
      throw new OptiqSemanticException("<=> is not yet supported for cbo.");
    }
    SqlOperator optiqOp = hiveToOptiq.get(hiveUdfName);
    if (optiqOp == null) {
      OptiqUDFInfo uInf = getUDFInfo(hiveUdfName, optiqArgTypes, optiqRetType);
      optiqOp = new SqlFunction(uInf.m_udfName, SqlKind.OTHER_FUNCTION, uInf.m_returnTypeInference,
          uInf.m_operandTypeInference, uInf.m_operandTypeChecker,
          SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    return optiqOp;
  }

  public static SqlAggFunction getOptiqAggFn(String hiveUdfName,
      ImmutableList<RelDataType> optiqArgTypes, RelDataType optiqRetType) {
    SqlAggFunction optiqAggFn = (SqlAggFunction) hiveToOptiq.get(hiveUdfName);
    if (optiqAggFn == null) {
      OptiqUDFInfo uInf = getUDFInfo(hiveUdfName, optiqArgTypes, optiqRetType);

      optiqAggFn = new OptiqUDAF(uInf.m_udfName, uInf.m_returnTypeInference,
          uInf.m_operandTypeInference, uInf.m_operandTypeChecker, uInf.m_argTypes, uInf.m_retType);
    }

    return optiqAggFn;
  }

  static class HiveToken {
    int      type;
    String   text;
    String[] args;

    HiveToken(int type, String text, String... args) {
      this.type = type;
      this.text = text;
      this.args = args;
    }
  }
}
