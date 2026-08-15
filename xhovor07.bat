set classpath=bin;cybelle;cybelle\Cybele.jar;cybelle\CybeleImpl.jar
java --patch-module java.base=cybelle -classpath %classpath% cz.vutbr.fit.ags.xhovor07.Main