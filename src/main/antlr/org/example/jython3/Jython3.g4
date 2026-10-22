/**
 * Jython3.g4 – ANTLR4 grammar for a Python‑3 subset.
 *
 * Supported constructs (Phase 5):
 *   • Integer and string literals
 *   • Variable assignment (e.g.  x = 10)
 *   • Arithmetic expressions with full operator precedence (+, -, *, /)
 *   • print() built‑in
 *   • if / else (single‑statement bodies on the same line)
 *   • while (single‑statement body on the same line)
 *   • import (from a local modules/ directory)
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
    : expr op=('*' | '/') expr          # MulDiv
    | expr op=('+' | '-') expr          # AddSub
    | ID LPAREN arg_list? RPAREN        # FuncCall
    | LPAREN expr RPAREN                # Paren
    | INT                               # Int
    | STRING                            # Str
    | ID                                # Id
    ;

// ─── Lexer rules ──────────────────────────────────────────────────────────────

// Keywords (must appear before ID rule so they take priority)
PRINT  : 'print';
IF     : 'if';
ELSE   : 'else';
WHILE  : 'while';
IMPORT : 'import';
DEF    : 'def';
RETURN : 'return';

// Operators & punctuation
ASSIGN : '=';
LPAREN : '(';
RPAREN : ')';
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
