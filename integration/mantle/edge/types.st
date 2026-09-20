(* A library file (no PROGRAM): in scope for every task. The Sparkplug edge
   publishes a tag of this type as a Template instance, which is what makes
   it worth having here: Mantle has to flatten it into a folder of member
   tags and turn a write to one member back into a partial template. *)

TYPE
  Motor : STRUCT
    Running : BOOL;
    Fault   : BOOL;
    Speed   : REAL;
    SpeedSP : REAL;
    Starts  : DINT;
  END_STRUCT;
END_TYPE
