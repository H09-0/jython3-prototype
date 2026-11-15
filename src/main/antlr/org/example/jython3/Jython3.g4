/**
 * Jython3.g4 – ANTLR4 grammar for Python 3 with true indentation blocks.
 *
 * Sprint A — added:
 *   - list / dict / set comprehensions
 *   - lambda expressions
 *   - global / nonlocal statements
 *   - with statement (single-item, optional 'as')
 *   - tuple unpacking assignment  (a, b = expr)
 *   - assert statement
 *   - f-string literals  (f"..." / f'...')
 *   - unary plus (+expr)
 *   - chained comparisons  (1 < x < 10)
 */
grammar Jython3;

tokens {
    INDENT,
    DEDENT
}

// ─── Parser rules ────────────────────────────────────────────────────────────

/** Top‑level entry point – statements separated by newlines or semicolons. */
stat
    : (stmt (SEMI)? | NEWLINE)* EOF
    ;

stmt
    : simple_stmt
    | compound_stmt
    ;

/** A suite is either a single line or an indented block of statements. */
suite
    : simple_stmt (SEMI simple_stmt)* (NEWLINE | EOF)?
    | NEWLINE INDENT (stmt (SEMI)? | NEWLINE)+ DEDENT
    ;

simple_stmt
    : assign_stmt
    | aug_assign_stmt
    | import_stmt
    | return_stmt
    | raise_stmt
    | del_stmt
    | pass_stmt
    | break_stmt
    | continue_stmt
    | execfile_stmt
    | yield_stmt
    | global_stmt
    | nonlocal_stmt
    | assert_stmt
    | expr_stmt
    ;

/** Unified assignment (simple, chained, attribute, subscript, and tuple unpacking) */
assign_stmt
    : (target_list ASSIGN)+ (yield_expr | rhs_tuple)
    ;

/** Right-hand side that may itself be a tuple: 1, 2  or  (1, 2)  or  expr */
rhs_tuple
    : expr (COMMA expr)+ COMMA?   # RhsTupleMany
    | expr                          # RhsSingle
    ;

/** Augmented assignment: x += expr, obj.attr += expr, items[0] += expr, matrix[0][1] *= expr */
aug_assign_stmt
    : single_target aug_op expr
    ;

/** One or more assignment/deletion targets (comma-separated -> TupleTarget). */
target_list
    : single_target (COMMA single_target)* COMMA?
    ;

single_target
    : expr LBRACK slice_item RBRACK    # TargetSubscript
    | expr DOT ID                      # TargetAttr
    | LPAREN target_list RPAREN        # TargetParenTuple
    | LBRACK target_list RBRACK        # TargetListTuple
    | ID                               # TargetName
    ;

aug_op
    : PLUS_ASSIGN
    | MINUS_ASSIGN
    | MUL_ASSIGN
    | DIV_ASSIGN
    | MOD_ASSIGN
    | FLOORDIV_ASSIGN
    | POWER_ASSIGN
    | BIT_AND_ASSIGN
    | BIT_OR_ASSIGN
    | BIT_XOR_ASSIGN
    | LSHIFT_ASSIGN
    | RSHIFT_ASSIGN
    ;

/** import module_name or from module import name */
import_stmt
    : IMPORT dotted_as_name (COMMA dotted_as_name)*
    | FROM dotted_name IMPORT (
        MUL
        | LPAREN import_as_name (COMMA import_as_name)* COMMA? RPAREN
        | import_as_name (COMMA import_as_name)*
      )
    ;

dotted_as_name
    : dotted_name (AS ID)?
    ;

import_as_name
    : ID (AS ID)?
    ;

dotted_name
    : ID (DOT ID)*
    ;

/** raise [expr] — bare raise re-raises the active exception */
raise_stmt
    : RAISE (expr (FROM expr)?)?
    ;

/** return [rhs_tuple] */
return_stmt
    : RETURN rhs_tuple?
    ;

/** yield [from expr | rhs_tuple] */
yield_stmt
    : yield_expr
    ;

yield_expr
    : YIELD FROM expr
    | YIELD rhs_tuple?
    ;

/** del target_list */
del_stmt
    : DEL target_list
    ;

/** pass – no-op */
pass_stmt
    : PASS
    ;

/** break – exit loop */
break_stmt
    : BREAK
    ;

/** continue – next iteration */
continue_stmt
    : CONTINUE
    ;

/** execfile(path_expr) – execute a Python source file */
execfile_stmt
    : EXECFILE LPAREN expr RPAREN
    ;

/** Standalone expression statement: expr or comma-separated tuple, result discarded */
expr_stmt
    : rhs_tuple
    ;

/** global name, name, ... */
global_stmt
    : GLOBAL ID (COMMA ID)*
    ;

/** nonlocal name, name, ... */
nonlocal_stmt
    : NONLOCAL ID (COMMA ID)*
    ;

/** assert expr [, expr] */
assert_stmt
    : ASSERT expr (COMMA expr)?
    ;

compound_stmt
    : decorated_func_def
    | decorated_class_def
    | if_stmt
    | while_stmt
    | for_stmt
    | try_stmt
    | with_stmt
    | func_def
    | class_def
    ;

/** Decorator: @ expr NEWLINE */
decorator
    : '@' expr NEWLINE
    ;

decorated_func_def
    : decorator+ func_def
    ;

decorated_class_def
    : decorator+ class_def
    ;

/** if expr : suite (elif expr : suite)* (else : suite)? */
if_stmt
    : IF expr COLON suite (ELIF expr COLON suite)* (ELSE COLON suite)?
    ;

/** while expr : suite */
while_stmt
    : WHILE expr COLON suite
    ;

/** for target_list in expr : suite */
for_stmt
    : FOR target_list IN expr COLON suite
    ;

/** try: suite except [ExcType [as var]]: suite [finally: suite] */
try_stmt
    : TRY COLON suite except_clause+ (ELSE COLON suite)? (FINALLY COLON suite)?
    | TRY COLON suite FINALLY COLON suite
    ;

except_clause
    : EXCEPT (expr (AS ID)?)? COLON suite
    ;

/** with item [, item]* : suite */
with_stmt
    : WITH with_item (COMMA with_item)* COLON suite
    ;

with_item
    : expr (AS ID)?
    ;

/** def name(params): suite */
func_def
    : DEF ID LPAREN param_list? RPAREN COLON suite
    ;

/** class Name[(Bases)]: suite */
class_def
    : CLASS ID (LPAREN arg_list RPAREN)? COLON suite
    ;

/**
 * Parameter list for function definitions.
 * Supports: positional, default (x=val), *args, **kwargs.
 * Order enforced at compile-time: positional → defaults → *args → **kwargs.
 */
param_list
    : param_item (COMMA param_item)* COMMA?
    ;

param_item
    : POWER ID       # KwargsParam
    | MUL ID         # ArgsParam
    | ID ASSIGN expr # DefaultParam
    | ID             # SimpleParam
    ;

/**
 * Argument list for function calls.
 * Supports: positional, keyword (name=val), *splat, **splatKw.
 */
arg_list
    : gen_expr
    | arg_item (COMMA arg_item)* COMMA?
    ;

arg_item
    : POWER expr      # SplatKwArg
    | MUL expr        # SplatArg
    | ID ASSIGN expr  # KeywordArg
    | expr            # PositionalArg
    ;

/**
 * Expression grammar with precedence.
 *
 * Comprehension forms are added as alternatives inside the collection literals.
 * Lambda is added as a low-precedence alternative.
 */
expr
    : expr LBRACK slice_item RBRACK                        # Subscript
    | expr LPAREN arg_list? RPAREN                         # CallExpr
    | expr DOT ID                                          # AttrAccess
    | <assoc=right> expr POWER expr                        # Power
    | SUB expr                                             # UnaryMinus
    | ADD expr                                             # UnaryPlus
    | BIT_NOT expr                                         # BitwiseInvert
    | expr op=('*' | '/' | FLOORDIV | '%') expr            # MulDivMod
    | expr op=('+' | '-') expr                             # AddSub
    | expr op=(LSHIFT | RSHIFT) expr                       # ShiftExpr
    | expr BIT_AND expr                                    # BitAndExpr
    | expr BIT_XOR expr                                    # BitXorExpr
    | expr BIT_OR expr                                     # BitOrExpr
    | expr cmp_op expr                                     # Comparison
    | NOT expr                                             # NotExpr
    | expr AND expr                                        # AndExpr
    | expr OR expr                                         # OrExpr
    | expr IF expr ELSE expr                               # TernaryExpr
    | LAMBDA param_list? COLON expr                        # LambdaExpr
    | ID WALRUS expr                                       # WalrusExpr
    | LPAREN yield_expr RPAREN                             # ParenYieldExpr
    | LPAREN gen_expr RPAREN                               # GenExpr
    | LPAREN expr RPAREN                                   # Paren
    | LPAREN expr_list? RPAREN                             # TupleLiteral
    | LBRACK expr FOR ID IN expr (IF expr)? RBRACK         # ListComp
    | LBRACK expr_list? RBRACK                             # ListLiteral
    | LBRACE expr COLON expr FOR ID IN expr (IF expr)? RBRACE  # DictComp
    | LBRACE expr FOR ID IN expr (IF expr)? RBRACE         # SetComp
    | LBRACE dict_entries? RBRACE                          # DictLiteral
    | LBRACE expr_list RBRACE                              # SetLiteral
    | FLOAT                                                # Float
    | INT                                                  # Int
    | FSTRING                                              # FStr
    | BSTRING                                              # BStr
    | RSTRING                                              # RStr
    | STRING                                               # Str
    | TRUE                                                 # TrueLit
    | FALSE                                                # FalseLit
    | NONE                                                 # NoneLit
    | ID                                                   # Id
    ;

/** subscript or slice index, e.g. i or a:b or a:b:c or :b or a: or ::-1 */
slice_item
    : COLON COLON expr                                # SliceDoubleColonStep
    | COLON expr COLON expr?                          # SliceOmittedStartStep
    | COLON expr                                      # SliceOmittedStart
    | COLON                                           # SliceOmittedAll
    | expr COLON COLON expr?                          # SliceStartDoubleColonStep
    | expr COLON expr (COLON expr?)?                  # SliceFull
    | expr COLON                                      # SliceStartOnly
    | expr                                            # SliceSingleIndex
    ;
expr_list
    : expr (COMMA expr)* COMMA?
    ;

/** Comma-separated key:value pairs for dict literals. */
dict_entries
    : dict_entry (COMMA dict_entry)* COMMA?
    ;

dict_entry
    : expr COLON expr
    ;

/** Lazy generator expression: expr (for target in iter [if cond]*)+ */
gen_expr
    : expr comp_for+
    ;

comp_for
    : FOR target_list IN expr comp_if*
    ;

comp_if
    : IF expr
    ;

cmp_op
    : EQ | NEQ | LTE | GTE | LT | GT
    | NOT IN
    | IN
    | IS NOT
    | IS
    ;

// ─── Lexer rules ──────────────────────────────────────────────────────────────

// Keywords
IF       : 'if';
ELIF     : 'elif';
ELSE     : 'else';
WHILE    : 'while';
FOR      : 'for';
IN       : 'in';
IS       : 'is';
TRY      : 'try';
EXCEPT   : 'except';
RAISE    : 'raise';
FINALLY  : 'finally';
IMPORT   : 'import';
FROM     : 'from';
AS       : 'as';
DEF      : 'def';
CLASS    : 'class';
RETURN   : 'return';
YIELD    : 'yield';
DEL      : 'del';
PASS     : 'pass';
BREAK    : 'break';
CONTINUE : 'continue';
EXECFILE : 'execfile';
AND      : 'and';
OR       : 'or';
NOT      : 'not';
TRUE     : 'True';
FALSE    : 'False';
NONE     : 'None';
WITH     : 'with';
GLOBAL   : 'global';
NONLOCAL : 'nonlocal';
ASSERT   : 'assert';
LAMBDA   : 'lambda';

// Multi-character operators
PLUS_ASSIGN     : '+=';
MINUS_ASSIGN    : '-=';
MUL_ASSIGN      : '*=';
DIV_ASSIGN      : '/=';
MOD_ASSIGN      : '%=';
FLOORDIV_ASSIGN : '//=';
POWER_ASSIGN    : '**=';
LSHIFT_ASSIGN   : '<<=';
RSHIFT_ASSIGN   : '>>=';
BIT_AND_ASSIGN  : '&=';
BIT_OR_ASSIGN   : '|=';
BIT_XOR_ASSIGN  : '^=';
POWER           : '**';
FLOORDIV        : '//';
LSHIFT          : '<<';
RSHIFT          : '>>';
EQ              : '==';
NEQ             : '!=';
LTE             : '<=';
GTE             : '>=';
WALRUS          : ':=';

// Single-character punctuation & operators
ASSIGN  : '=';
LT      : '<';
GT      : '>';
BIT_AND : '&';
BIT_OR  : '|';
BIT_XOR : '^';
BIT_NOT : '~';
DOT     : '.';

LPAREN : '(';
RPAREN : ')';
LBRACK : '[';
RBRACK : ']';
LBRACE : '{';
RBRACE : '}';
COLON  : ':';
SEMI   : ';';
COMMA  : ',';
MUL    : '*';
DIV    : '/';
ADD    : '+';
SUB    : '-';
MOD    : '%';
AT     : '@';

// Literals — FSTRING must come before STRING so the lexer picks the right rule
ID     : [a-zA-Z_][a-zA-Z0-9_]*;
FLOAT  : [0-9] ('_'? [0-9])* '.' ([0-9] ('_'? [0-9])*)? ([eE] [+-]? [0-9] ('_'? [0-9])*)?
       | '.' [0-9] ('_'? [0-9])* ([eE] [+-]? [0-9] ('_'? [0-9])*)?
       | [0-9] ('_'? [0-9])* [eE] [+-]? [0-9] ('_'? [0-9])*
       ;
INT    : '0' [xX] ('_'? [0-9a-fA-F])+
       | '0' [bB] ('_'? [01])+
       | '0' [oO] ('_'? [0-7])+
       | [0-9] ('_'? [0-9])*
       ;

/**
 * f-strings: f'...' / f"..." (single or double quote, single-line only).
 * Triple-quoted f-strings are intentionally left for a future sprint.
 * The raw token text is processed at compile time.
 */
FSTRING
    : [fF] '\'' ( ~['\\\r\n] | '\\' . )* '\''
    | [fF] '"'  ( ~["\\\r\n] | '\\' . )* '"'
    ;

BSTRING
    : [bB] '\'\'\'' ( ~['\\] | '\\' . | '\'' ~['] | '\'\'' ~['] )* '\'\'\''
    | [bB] '"""' ( ~["\\] | '\\' . | '"' ~["] | '""' ~["] )* '"""'
    | [bB] '\'' (~['\\\r\n] | '\\' .)* '\''
    | [bB] '"'  (~["\\\r\n] | '\\' .)* '"'
    ;

RSTRING
    : [rR] '\'\'\'' ( ~['\\] | '\\' . | '\'' ~['] | '\'\'' ~['] )* '\'\'\''
    | [rR] '"""' ( ~["\\] | '\\' . | '"' ~["] | '""' ~["] )* '"""'
    | [rR] '\'' (~['\\] | '\\' .)* '\''
    | [rR] '"'  (~["\\] | '\\' .)* '"'
    ;

STRING
    : [uU]? '\'' (~['\\] | '\\' .)* '\''
    | [uU]? '"'  (~["\\] | '\\' .)* '"'
    | [uU]? '\'\'\'' ( ~['\\] | '\\' . | '\'' ~['] | '\'\'' ~['] )* '\'\'\''
    | [uU]? '"""' ( ~["\\] | '\\' . | '"' ~["] | '""' ~["] )* '"""'
    ;

// Newline with following line's leading whitespace
NEWLINE : ( '\r'? '\n' | '\r' ) [ \t]* ;

// Horizontal whitespace ignored
WS : [ \t]+ -> skip;

// Comments (# ...) ignored
LINE_COMMENT : '#' ~[\r\n]* -> skip;
