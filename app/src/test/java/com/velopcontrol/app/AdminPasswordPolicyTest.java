package com.velopcontrol.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdminPasswordPolicyTest {
    @Test public void modernPasswordRequiresAllCharacterClasses(){AdminPasswordPolicy.validate("Abcde123!x",true);for(String s:new String[]{"short1!A","abcdefgh1!","ABCDEFGH1!","Abcdefgh!x","Abcdefgh12"})assertThrows(IllegalArgumentException.class,()->AdminPasswordPolicy.validate(s,true));}
    @Test public void duplicateAndNonAsciiCharactersAreRejected(){for(String s:new String[]{"Abccdef123!","Abcdef123!é","Abcdef123!\n","Ab"+"c".repeat(64)+"1!"})assertThrows(IllegalArgumentException.class,()->AdminPasswordPolicy.validate(s,true));}
    @Test public void specialCharactersFollowExactFirmwareRanges(){assertThrows(IllegalArgumentException.class,()->AdminPasswordPolicy.validate("Abcdef123{",true));AdminPasswordPolicy.validate("Abcdef123}",true);}
    @Test public void olderPolicyDoesNotImposeNewFirmwareRequirements(){AdminPasswordPolicy.validate("existing-password",false);assertThrows(IllegalArgumentException.class,()->AdminPasswordPolicy.validate("",false));}
}
