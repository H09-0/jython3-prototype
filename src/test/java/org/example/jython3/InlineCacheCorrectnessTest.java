package org.example.jython3;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyStr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class InlineCacheCorrectnessTest {

    @Test
    @DisplayName("Method call -> replace method on class -> method call sees updated method")
    void testReplaceMethodOnClassAfterCall() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Greeter:
                def say(self):
                    return "hello"

            g = Greeter()
            first = g.say()

            def new_say(self):
                return "goodbye"

            Greeter.say = new_say
            second = g.say()
            """);
        assertEquals(new PyStr("hello"), interp.getGlobal("first"));
        assertEquals(new PyStr("goodbye"), interp.getGlobal("second"));
    }

    @Test
    @DisplayName("Modify class member after LOAD_ATTR inline cache has been primed")
    void testModifyClassMemberAfterCachePrimed() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Counter:
                step = 1
                def get_step(self):
                    return self.step

            c = Counter()
            # Prime cache
            for _ in range(10):
                r1 = c.get_step()

            # Mutate class member
            Counter.step = 10

            # Must read new value, not stale cached value
            r2 = c.get_step()
            """);
        assertEquals(new PyInt(1), interp.getGlobal("r1"));
        assertEquals(new PyInt(10), interp.getGlobal("r2"));
    }

    @Test
    @DisplayName("Modify method in base class and see the change reflected in subclass")
    void testBaseClassMethodModificationPropagation() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Base:
                def action(self):
                    return "base_v1"

            class Sub(Base):
                pass

            s = Sub()
            # Prime cache through subclass
            for _ in range(10):
                r1 = s.action()

            def action_v2(self):
                return "base_v2"

            Base.action = action_v2
            # Subclass must see the updated base method
            r2 = s.action()
            """);
        assertEquals(new PyStr("base_v1"), interp.getGlobal("r1"));
        assertEquals(new PyStr("base_v2"), interp.getGlobal("r2"));
    }

    @Test
    @DisplayName("Add field to instance after earlier lookup failure")
    void testAddFieldAfterFailedLookup() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Item:
                pass

            it = Item()
            failed = False
            try:
                x = it.price
            except AttributeError:
                failed = True

            it.price = 99
            success = it.price
            """);
        assertEquals(org.example.jython3.runtime.PyBool.TRUE, interp.getGlobal("failed"));
        assertEquals(new PyInt(99), interp.getGlobal("success"));
    }

    @Test
    @DisplayName("Instance field shadows class method after caching")
    void testInstanceFieldShadowsClassMethod() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Node:
                def value(self):
                    return 100

            n = Node()
            m1 = n.value()

            # Set field on instance with same name as method
            n.value = 500
            m2 = n.value
            """);
        assertEquals(new PyInt(100), interp.getGlobal("m1"));
        assertEquals(new PyInt(500), interp.getGlobal("m2"));
    }

    @Test
    @DisplayName("Polymorphic access: multiple classes at the same LOAD_ATTR site")
    void testPolymorphicLoadAttr() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Cat:
                def speak(self):
                    return "meow"

            class Dog:
                def speak(self):
                    return "woof"

            animals = [Cat(), Dog(), Cat(), Dog()]
            sounds = []
            for a in animals:
                sounds.append(a.speak())
            """);
        org.example.jython3.runtime.PyList sounds = (org.example.jython3.runtime.PyList) interp.getGlobal("sounds");
        assertEquals(4, sounds.items().size());
        assertEquals("meow", sounds.items().get(0).toString());
        assertEquals("woof", sounds.items().get(1).toString());
        assertEquals("meow", sounds.items().get(2).toString());
        assertEquals("woof", sounds.items().get(3).toString());
    }

    @Test
    @DisplayName("setattr on class triggers hierarchy invalidation")
    void testSetattrOnClassInvalidatesCache() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Service:
                def status(self):
                    return "running"

            s = Service()
            r1 = s.status()

            def stopped(self):
                return "stopped"

            setattr(Service, "status", stopped)
            r2 = s.status()
            """);
        assertEquals(new PyStr("running"), interp.getGlobal("r1"));
        assertEquals(new PyStr("stopped"), interp.getGlobal("r2"));
    }

    @Test
    @DisplayName("PyClass.members() is unmodifiable to prevent bypassing cache invalidation")
    void testMembersMapIsUnmodifiable() {
        var pyClass = new org.example.jython3.runtime.PyClass("Test", null, new java.util.HashMap<>());
        assertThrows(UnsupportedOperationException.class, () -> {
            pyClass.members().put("hack", new org.example.jython3.runtime.PyInt(1));
        });
    }
}
