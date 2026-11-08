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
    : print_stmt
    | assign_stmt
    | tuple_assign_stmt
    | subscript_assign_stmt
    | aug_assign_stmt
    | import_stmt
    | return_stmt
    | raise_stmt
    | del_stmt
    | pass_stmt
    | break_stmt
    | continue_stmt
    | execfile_stmt
    | call_stmt
    | yield_stmt
    | global_stmt
    | nonlocal_stmt
    | assert_stmt
    ;

/** print() or print(a) or print(a, b, c) */
print_stmt
    : PRINT LPAREN arg_list? RPAREN
    ;

/** identifier = expr  or  identifier = identifier = expr  (chained assignment) */
assign_stmt
    : ID ASSIGN expr
    | ID ASSIGN ID ASSIGN expr
    ;

/** Tuple unpacking: a, b = expr  or  a, b = x, y  or  (a, b) = expr */
tuple_assign_stmt
    : LPAREN ID (COMMA ID)+ RPAREN ASSIGN rhs_tuple
    | ID (COMMA ID)+ ASSIGN rhs_tuple
    ;

/** Right-hand side that may itself be a tuple: 1, 2  or  (1, 2)  or  expr */
rhs_tuple
    : expr (COMMA expr)+ COMMA?   # RhsTupleMany
    | expr                          # RhsSingle
    ;

/** container[key] = expr or obj.attr = expr */
subscript_assign_stmt
    : expr LBRACK expr RBRACK ASSIGN expr
    | expr DOT ID ASSIGN expr
    ;

/** Augmented assignment: x += expr, x -= expr, etc. */
aug_assign_stmt
    : ID aug_op expr
    ;

aug_op
    : PLUS_ASSIGN
    | MINUS_ASSIGN
    | MUL_ASSIGN
    | DIV_ASSIGN
    | MOD_ASSIGN
    | FLOORDIV_ASSIGN
    | POWER_ASSIGN
    ;

/** import module_name or from module import name */
import_stmt
    : IMPORT dotted_as_name (COMMA dotted_as_name)*
    | FROM dotted_name IMPORT (MUL | import_as_name (COMMA import_as_name)*)
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
    : RAISE expr?
    ;

/** return [expr] */
return_stmt
    : RETURN expr?
    ;

/** yield [expr] */
yield_stmt
    : YIELD expr?
    ;

/** del variable or del container[key] */
del_stmt
    : DEL ID
    | DEL expr LBRACK expr RBRACK
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

/** Standalone function call: name(args) or expr.method(args) */
call_stmt
    : ID LPAREN arg_list? RPAREN
    | expr DOT ID LPAREN arg_list? RPAREN
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

/** for id in expr : suite */
for_stmt
    : FOR ID IN expr COLON suite
    ;

/** try: suite except [ExcType [as var]]: suite [finally: suite] */
try_stmt
    : TRY COLON suite except_clause+ (ELSE COLON suite)? (FINALLY COLON suite)?
    | TRY COLON suite FINALLY COLON suite
    ;

except_clause
    : EXCEPT (expr (AS ID)?)? COLON suite
    ;

/** with expr [as id]: suite */
with_stmt
    : WITH expr (AS ID)? COLON suite
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
    : arg_item (COMMA arg_item)* COMMA?
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
    | expr DOT ID LPAREN arg_list? RPAREN                  # AttrCall
    | expr DOT ID                                          # AttrAccess
    | <assoc=right> expr POWER expr                        # Power
    | SUB expr                                             # UnaryMinus
    | ADD expr                                             # UnaryPlus
    | NOT expr                                             # NotExpr
    | expr op=('*' | '/' | FLOORDIV | '%') expr            # MulDivMod
    | expr op=('+' | '-') expr                             # AddSub
    | expr op=(EQ | NEQ | LTE | GTE | LT | GT) expr        # Comparison
    | expr NOT IN expr                                     # NotInExpr
    | expr IN expr                                         # InExpr
    | expr IS NOT expr                                     # IsNotExpr
    | expr IS expr                                         # IsExpr
    | expr AND expr                                        # AndExpr
    | expr OR expr                                         # OrExpr
    | expr IF expr ELSE expr                               # TernaryExpr
    | LAMBDA param_list? COLON expr                        # LambdaExpr
    | ID LPAREN arg_list? RPAREN                           # FuncCall
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

// ─── Lexer rules ──────────────────────────────────────────────────────────────

// Keywords
PRINT    : 'print';
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
POWER           : '**';
FLOORDIV        : '//';
EQ              : '==';
NEQ             : '!=';
LTE             : '<=';
GTE             : '>=';

// Single-character punctuation & operators
ASSIGN : '=';
LT     : '<';
GT     : '>';
DOT    : '.';

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
FLOAT  : [0-9]+ '.' [0-9]* | '.' [0-9]+;
INT    : [0-9]+;

/**
 * f-strings: f'...' / f"..." (single or double quote, single-line only).
 * Triple-quoted f-strings are intentionally left for a future sprint.
 * The raw token text is processed at compile time.
 */
FSTRING
    : [fF] '\'' ( ~['\\\r\n] | '\\' . )* '\''
    | [fF] '"'  ( ~["\\\r\n] | '\\' . )* '"'
    ;

STRING
    : '\'' (~['\\] | '\\' .)* '\''
    | '"'  (~["\\] | '\\' .)* '"'
    | '\'\'\'' ( ~['\\] | '\\' . | '\'' ~['] | '\'\'' ~['] )* '\'\'\''
    | '"""' ( ~["\\] | '\\' . | '"' ~["] | '""' ~["] )* '"""'
    ;

// Newline with following line's leading whitespace
NEWLINE : ( '\r'? '\n' | '\r' ) [ \t]* ;

// Horizontal whitespace ignored
WS : [ \t]+ -> skip;

// Comments (# ...) ignored
LINE_COMMENT : '#' ~[\r\n]* -> skip;
