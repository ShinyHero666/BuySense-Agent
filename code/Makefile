MODULE := java-control-plane

.PHONY: run test package

run:
	cd $(MODULE) && mvn spring-boot:run

test:
	cd $(MODULE) && mvn clean test

package:
	cd $(MODULE) && mvn clean package
