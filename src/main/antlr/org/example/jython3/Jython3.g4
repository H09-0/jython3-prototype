/**
 * Jython3.g4 – ANTLR4 grammar for a Python‑3 subset.
 *
 * Supported constructs:
 *   • Integer and string literals
 *   • Variable assignment (e.g.  x = 10)
 *   • Arithmetic expressions with full operator precedence (+, -, *, /)
 *   • print() built‑in
 *   • if / else (single‑statement bodies on the same line)
 *   • while (single‑statement body on the same line)
 *   • import (from a local modules/ directory)
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

compound_stmt
    : if_stmt
    | while_stmt
    ;

/** if expr : simple_stmt (else : simple_stmt)? */
if_stmt
    : IF expr COLON simple_stmt (ELSE COLON simple_stmt)?
    ;

/** while expr : simple_stmt */
while_stmt
    : WHILE expr COLON simple_stmt
    ;

/**
 * Expression grammar with precedence.
 * Highest precedence first (ANTLR4 resolves ambiguity by rule order).
 */
expr
    : expr op=('*' | '/') expr   # MulDiv
    | expr op=('+' | '-') expr   # AddSub
    | LPAREN expr RPAREN         # Paren
    | INT                        # Int
    | STRING                     # Str
    | ID                         # Id
    ;

// ─── Lexer rules ──────────────────────────────────────────────────────────────

// Keywords (must appear before ID rule so they take priority)
PRINT  : 'print';
IF     : 'if';
ELSE   : 'else';
WHILE  : 'while';
IMPORT : 'import';

// Operators & punctuation
ASSIGN : '=';
LPAREN : '(';
RPAREN : ')';
COLON  : ':';
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
