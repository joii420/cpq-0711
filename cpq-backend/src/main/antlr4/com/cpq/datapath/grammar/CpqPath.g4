/**
 * CpqPath.g4 — CPQ 系统变量路径 BNF 语法 (v5.1 §3.1 TECH-1)
 *
 * 支持：
 *   - 英文路径：mat_part.part_no
 *   - 中文 Sheet 名：元素BOM[元素='Ag'].组成含量
 *   - 嵌套路径（最多 3 层）：A[k='v'].B[k='v'].C.field
 *   - IN 谓词：mat_part[customer_id IN ('uuid1','uuid2')]
 *   - LIKE 谓词：mat_part[part_no LIKE '%abc%']
 *   - AND 多条件谓词：table[a='x' AND b='y']
 *   - 大小写敏感（默认 ANTLR 行为）
 *   - 空白容忍：a [ k = 'v' ] . b
 *
 * 设计说明：
 *   pathExpr 由 DOT 分隔的 segment 列表组成。每个 segment 是一个 identifier
 *   加可选的 [filterExpr]。语义上，末尾无 [] 的段可能是 "字段名" 或 "表名"，
 *   由上层 CpqPathParser.java 的 AST 构建器根据上下文判断：
 *     - 若最后一个 segment 没有谓词且前面已有至少一段，则作为 leafField。
 *     - 否则全部作为 tableRef（无字段投影）。
 *
 * 生成代码包：com.cpq.datapath.grammar
 */
grammar CpqPath;

// ══════════════════════════════════════════════════════════════
// Parser Rules
// ══════════════════════════════════════════════════════════════

/**
 * 顶层路径表达式：一个或多个 segment，以 DOT 分隔。
 *
 * 例如：
 *   mat_part                           → 1 segment, no field
 *   mat_part.part_no                   → 2 segments (last = field)
 *   元素BOM[元素='Ag'].组成含量         → 2 segments (last = field)
 *   A[k='v'].B[k='v'].C.field          → 4 segments (last = field)
 */
pathExpr
    : segment ( DOT segment )*
    ;

/**
 * 路径段：identifier + 可选谓词
 *   mat_part
 *   元素BOM[元素='Ag']
 *   mat_part[customer_id IN ('uuid1','uuid2')]
 */
segment
    : identifier ( LBRACKET filterExpr RBRACKET )?
    ;

/**
 * 谓词过滤表达式，支持 AND（逗号等价）分隔的多条件
 */
filterExpr
    : filterTerm ( ( AND | COMMA ) filterTerm )*
    ;

/**
 * 单个过滤条件：field op value
 */
filterTerm
    : identifier op operand
    ;

/**
 * 比较操作符（无 label，直接通过 OpContext.EQ()/NEQ()/... 访问）
 */
op
    : EQ
    | NEQ
    | GT
    | LT
    | GTE
    | LTE
    | IN
    | LIKE
    ;

/**
 * 操作数：字面量、变量引用、嵌套路径表达式（嵌套查询）
 */
operand
    : literal
    | variableRef
    | pathExpr
    ;

/**
 * 字面量
 */
literal
    : stringLiteral
    | numberLiteral
    | booleanLiteral
    | arrayLiteral
    ;

/**
 * 数组字面量，用于 IN 操作符
 *   ('val1', 'val2', 'val3')
 *
 * 注：v5.1 BNF 也允许 [val,...] 形式，但括号形式与 segment 的谓词 []
 * 语法不冲突（圆括号），故 v1 只实现圆括号形式，方括号形式保留语法注释。
 */
arrayLiteral
    : LPAREN literal ( COMMA literal )* RPAREN
    ;

stringLiteral
    : STRING
    ;

numberLiteral
    : NUMBER
    ;

booleanLiteral
    : TRUE
    | FALSE
    ;

/**
 * 变量引用：$varName
 */
variableRef
    : DOLLAR identifier
    ;

/**
 * 标识符：支持英文 + 中文 + 数字 + 下划线 + 括号 + 百分号
 * 保留关键字（AND/IN/LIKE/TRUE/FALSE）在此上下文中可作标识符
 */
identifier
    : IDENT
    | AND
    | IN
    | LIKE
    | TRUE
    | FALSE
    ;

// ══════════════════════════════════════════════════════════════
// Lexer Rules
// ══════════════════════════════════════════════════════════════

// ── 关键字（大小写敏感） ──
AND     : 'AND' ;
IN      : 'IN' ;
LIKE    : 'LIKE' ;
TRUE    : 'true' ;
FALSE   : 'false' ;

// ── 操作符 ──
EQ      : '=' ;
NEQ     : '!=' ;
GTE     : '>=' ;
LTE     : '<=' ;
GT      : '>' ;
LT      : '<' ;

// ── 分隔符 ──
DOT     : '.' ;
COMMA   : ',' ;
LBRACKET: '[' ;
RBRACKET: ']' ;
LPAREN  : '(' ;
RPAREN  : ')' ;
DOLLAR  : '$' ;

// ── 字符串字面量（单引号，支持 '' 转义） ──
STRING
    : '\'' ( ~'\'' | '\'\'' )* '\''
    ;

// ── 数字字面量 ──
NUMBER
    : [0-9]+ ( '.' [0-9]+ )?
    ;

// ── 标识符（英文 + 中文 + 特殊字符） ──
IDENT
    : IDENT_START IDENT_PART*
    ;

fragment IDENT_START
    : [a-zA-Z_]
    | CHINESE
    ;

fragment IDENT_PART
    : [a-zA-Z_0-9]
    | CHINESE
    | [()%]
    ;

fragment CHINESE
    : [\u4e00-\u9fff]
    ;

// ── 空白忽略 ──
WS
    : [ \t\r\n]+ -> skip
    ;
