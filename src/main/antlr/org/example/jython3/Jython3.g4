/**
 * Jython3.g4 – ANTLR4 grammar for a Python‑3 subset.
 *
 * Supported constructs (Phase 8):
 *   • Integer and string literals
 *   • List literals:  [1, 2, 3]
 *   • Variable assignment (e.g.  x = 10)
 *   • Arithmetic expressions with full operator precedence (+, -, *, /)
 *   • Subscript access:  list[index]
 *   • print() built‑in
 *   • if / else (single‑statement bodies on the same line)
 *   • while (single‑statement body on the same line)
 *   • for ... in ... : (single‑statement body)
 *   • import (from a local modules/ directory or built‑in modules)
 *   • Attribute access:   module.attr
 *   • Method/function call:  module.func(args)
 *   • Exception handling:  try / except / raise
 *   • Function definitions:  def name(params): body
 *   • Function calls:        name(args)
 *   • Return statements:     return expr
 */
grammar Jython3;

// ─── Parser rules ────────────────────────────────────────────────────────────

/** Top‑level entry point – a single statement. */
stat
    : simple_stmt EOF
    | compound_stmt EOF
    ;

simple_stmt
    : print_stmt
    | assign_stmt
    | import_stmt
    | return_stmt
    | raise_stmt
    | call_stmt
    ;

/** print(expr) */
print_stmt
    : PRINT LPAREN expr RPAREN
    ;

/** identifier = expr */
assign_stmt
    : ID ASSIGN expr
    ;

/** import module_name */
import_stmt
    : IMPORT ID
    ;

/** raise ExcType(msg) or raise ExcType */
raise_stmt
    : RAISE ID LPAREN arg_list? RPAREN
    | RAISE ID
    ;

/** return expr */
return_stmt
    : RETURN expr
    ;

/** Standalone function call:  name(args) */
call_stmt
    : ID LPAREN arg_list? RPAREN
    ;

compound_stmt
    : if_stmt
    | while_stmt
    | for_stmt
    | try_stmt
    | func_def
    ;

/** if expr : simple_stmt (else : simple_stmt)? */
if_stmt
    : IF expr COLON simple_stmt (ELSE COLON simple_stmt)?
    ;

/** while expr : simple_stmt */
while_stmt
    : WHILE expr COLON simple_stmt
    ;

/** for id in expr : simple_stmt */
for_stmt
    : FOR ID IN expr COLON simple_stmt
    ;

/** try: body except [ExcType]: handler */
try_stmt
    : TRY COLON simple_stmt EXCEPT ID COLON simple_stmt
    | TRY COLON simple_stmt EXCEPT COLON simple_stmt
    ;

/** def name(params): body */
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
    : expr (COMMA expr)*
    ;

/**
 * Expression grammar with precedence.
 * Highest precedence first (ANTLR4 resolves ambiguity by rule order).
 */
expr
    : expr LBRACK expr RBRACK              # Subscript
    | expr DOT ID LPAREN arg_list? RPAREN  # AttrCall
    | expr DOT ID                          # AttrAccess
    | expr op=('*' | '/') expr             # MulDiv
    | expr op=('+' | '-') expr             # AddSub
    | ID LPAREN arg_list? RPAREN           # FuncCall
    | LPAREN expr RPAREN                   # Paren
    | LBRACK expr_list? RBRACK             # ListLiteral
    | INT                                  # Int
    | STRING                               # Str
    | ID                                   # Id
    ;

/** Comma‑separated list of expressions (for list literals). */
expr_list
    : expr (COMMA expr)*
    ;

// ─── Lexer rules ──────────────────────────────────────────────────────────────

// Keywords (must appear before ID rule so they take priority)
PRINT  : 'print';
IF     : 'if';
ELSE   : 'else';
WHILE  : 'while';
FOR    : 'for';
IN     : 'in';
TRY    : 'try';
EXCEPT : 'except';
RAISE  : 'raise';
IMPORT : 'import';
DEF    : 'def';
RETURN : 'return';

// Operators & punctuation
DOT    : '.';
ASSIGN : '=';
LPAREN : '(';
RPAREN : ')';
LBRACK : '[';
RBRACK : ']';
COLON  : ':';
SEMI   : ';';
COMMA  : ',';
MUL    : '*';
DIV    : '/';
ADD    : '+';
SUB    : '-';

// Literals
ID     : [a-zA-Z_][a-zA-Z0-9_]*;
INT    : [0-9]+;
STRING
    : '\'' (~['\\] | '\\' .)* '\''
    | '"'  (~["\\] | '\\' .)* '"'
    ;

// Ignore whitespace
WS : [ \t\r\n]+ -> skip;
