package org.example.jython3.compile;

/**
 * Const-pool token for CALL_FUNCTION_KW instructions.
 *
 * <p>Packs both the positional-argument count and the keyword-argument names
 * into a single const-pool slot so that the 16-bit opcode argument can address
 * it with a plain pool index (the previous scheme tried to pack nPos into the
 * high 16 bits of the opcode argument, which is silently truncated to 16 bits
 * by {@link CodeBuilder#emitRaw}).
 *
 * @param nPos    number of positional arguments at the call site
 * @param kwNames ordered keyword argument names
 */
public record KwCallInfo(int nPos, String[] kwNames) {}
