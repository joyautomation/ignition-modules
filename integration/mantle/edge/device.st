PROGRAM Device
(* A device built to be tested against, not to look like a plant: every
   output is a pure function of something a test can set, so an assertion
   never has to guess what the value "should be by now".

     LevelSP      -> LevelFt        a value makes the round trip unchanged
     CountEnable  -> Counter        a steady stream of changes, on demand
     P101_Enable  -> P101.Running   a struct member driven from outside it
     P101.SpeedSP -> P101.Speed     a struct member driven from inside it,
                                    which is the one a host writes by
                                    sending a partial template
     P101_Enable  -> P101.Starts    counts rising edges: a true integer   *)
VAR_EXTERNAL
    LevelSP     : REAL;
    LevelFt     : REAL;
    CountEnable : BOOL;
    Counter     : DINT;
    Mode        : STRING;
    P101        : Motor;
    P101_Enable : BOOL;
END_VAR
VAR
    m          : Motor;
    wasEnabled : BOOL;
END_VAR

LevelFt := LevelSP;

IF CountEnable THEN
    Counter := Counter + 1;
END_IF;

IF CountEnable THEN Mode := 'COUNTING'; ELSE Mode := 'IDLE'; END_IF;

m := P101;
m.Running := P101_Enable AND NOT m.Fault;
IF m.Running THEN m.Speed := m.SpeedSP; ELSE m.Speed := 0.0; END_IF;
IF P101_Enable AND NOT wasEnabled THEN m.Starts := m.Starts + 1; END_IF;
wasEnabled := P101_Enable;
P101 := m;
