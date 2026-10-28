package com.corretorzap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"corretor.atalho.habilitado=false",
		"corretor.janela.habilitada=false",
		"corretor.config-dir=${java.io.tmpdir}/corretor-zap-teste" })
class CorretorZapApplicationTests {

	@Test
	void contextLoads() {
	}

}
