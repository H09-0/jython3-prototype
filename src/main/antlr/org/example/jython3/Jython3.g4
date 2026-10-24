/**
 * Jython3.g4 – ANTLR4 grammar for Python 3.
 *
 * Supported features (Jython 3 foundation for Jython 2.7 replacement):
 *   • Multi-statement scripts & lines (semicolon or newline separated)
 *   • Full comment support (#)
 *   • Data types: int, float, str (single, double, triple-quoted), bool (True/False), None, list, dict
 *   • Operators:
 *       - Arithmetic: +, -, *, /, //, %, **
 *       - Unary: -, not
 *       - Comparison: ==, !=, <, >, <=, >=, in, not in, is, is not
 *       - Logical: and, or
 *   • Assignments:
 *       - Regular: x = expr
 *       - Subscript: arr[idx] = expr, dict[key] = expr
 *       - Augmented: +=, -=, *=, /=, %=, //=, **=
 *   • Control Flow:
 *       - if / elif / else
 *       - while
 *       - for ... in ...
 *       - try / except [ExcType [as e]] / raise
 *       - pass, break, continue
 *   • Functions:
 *       - def name(p1, p2, ...): suite
 *       - return [expr]
 *       - Function calls: func(a1, a2, ...)
 *   • Modules:
 *       - import module
 *       - module.attr, module.func(args)
 *   • Builtins:
 *       - print() with 0, 1, or multiple arguments
 *       - del statement
 */
grammar Jython3;

// ─── Parser rules ────────────────────────────────────────────────────────────

/** Top‑level entry point – one or more statements until EOF. */
stat
    : (stmt (SEMI)?)* EOF
    ;

stmt
    : simple_stmt
    | compound_stmt
    ;

suite
    : simple_stmt (SEMI simple_stmt)*
    ;

simple_stmt
    : print_stmt
    | assign_stmt
    | subscript_assign_stmt
    | aug_assign_stmt
    | import_stmt
    | return_stmt
    | raise_stmt
    | del_stmt
    | pass_stmt
    | break_stmt
    | continue_stmt
    | call_stmt
    ;

/** print() or print(a) or print(a, b, c) */
print_stmt
    : PRINT LPAREN arg_list? RPAREN
    ;

/** identifier = expr */
assign_stmt
    : ID ASSIGN expr
    ;

/** container[key] = expr */
subscript_assign_stmt
    : expr LBRACK expr RBRACK ASSIGN expr
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

/** import module_name */
import_stmt
    : IMPORT ID
    ;

/** raise [ExcType[(msg)]] */
raise_stmt
    : RAISE ID LPAREN arg_list? RPAREN
    | RAISE ID
    | RAISE
    ;

/** return [expr] */
return_stmt
    : RETURN expr?
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

/** Standalone function call: name(args) or expr.method(args) */
call_stmt
    : ID LPAREN arg_list? RPAREN
    | expr DOT ID LPAREN arg_list? RPAREN
    ;

compound_stmt
    : if_stmt
    | while_stmt
    | for_stmt
    | try_stmt
    | func_def
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

/** try: suite except [ExcType]: suite */
try_stmt
    : TRY COLON suite EXCEPT ID COLON suite
    | TRY COLON suite EXCEPT COLON suite
    ;

/** def name(params): func_body */
func_def
    : DEF ID LPAREN param_list? RPAREN COLON func_body
    ;

/** Function body – one or more semicolon‑separated simple statements. */
func_body
    : simple_stmt (SEMI simple_stmt)*
    ;

/** Parameter list – comma‑separated identifiers. */
param_list
    : ID (COMMA ID)*
    ;

/** Argument list – comma‑separated expressions. */
arg_list
    : expr (COMMA expr)* COMMA?
    ;

/**
 * Expression grammar with precedence.
 * Highest precedence first (ANTLR4 resolves ambiguity by rule order).
 */
expr
    : expr LBRACK expr RBRACK                         # Subscript
    | expr DOT ID LPAREN arg_list? RPAREN             # AttrCall
    | expr DOT ID                                     # AttrAccess
    | <assoc=right> expr POWER expr                   # Power
    | SUB expr                                        # UnaryMinus
    | NOT expr                                        # NotExpr
    | expr op=('*' | '/' | FLOORDIV | '%') expr       # MulDivMod
    | expr op=('+' | '-') expr                        # AddSub
    | expr op=(EQ | NEQ | LTE | GTE | LT | GT) expr   # Comparison
    | expr NOT IN expr                                # NotInExpr
    | expr IN expr                                    # InExpr
    | expr IS NOT expr                                # IsNotExpr
    | expr IS expr                                    # IsExpr
    | expr AND expr                                   # AndExpr
    | expr OR expr                                    # OrExpr
    | ID LPAREN arg_list? RPAREN                      # FuncCall
    | LPAREN expr RPAREN                              # Paren
    | LBRACK expr_list? RBRACK                        # ListLiteral
    | LBRACE dict_entries? RBRACE                     # DictLiteral
    | FLOAT                                           # Float
    | INT                                             # Int
    | STRING                                          # Str
    | TRUE                                            # TrueLit
    | FALSE                                           # FalseLit
    | NONE                                            # NoneLit
    | ID                                              # Id
    ;

/** Comma‑separated list of expressions (for list literals). */
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

// Keywords (must appear before ID rule)
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
IMPORT   : 'import';
DEF      : 'def';
RETURN   : 'return';
DEL      : 'del';
PASS     : 'pass';
BREAK    : 'break';
CONTINUE : 'continue';
AND      : 'and';
OR       : 'or';
NOT      : 'not';
TRUE     : 'True';
FALSE    : 'False';
NONE     : 'None';

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

// Literals
ID     : [a-zA-Z_][a-zA-Z0-9_]*;
FLOAT  : [0-9]+ '.' [0-9]* | '.' [0-9]+;
INT    : [0-9]+;
STRING
    : '\'' (~['\\] | '\\' .)* '\''
    | '"'  (~["\\] | '\\' .)* '"'
    | '\'\'\'' ( ~['\\] | '\\' . | '\'' ~['] | '\'\'' ~['] )* '\'\'\''
    | '"""' ( ~["\\] | '\\' . | '"' ~["] | '""' ~["] )* '"""'
    ;

// Whitespace ignored
WS : [ \t\r\n]+ -> skip;

// Comments (# ...) ignored
LINE_COMMENT : '#' ~[\r\n]* -> skip;
