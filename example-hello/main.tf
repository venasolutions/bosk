
terraform {
	required_providers {
		bosk = {
			source = "vena/bosk"
			version = "0.0.1"
		}
	}
}

provider "bosk" {
}

resource "bosk_node" "targets" {
	url = "http://localhost:1740/bosk/targets"
	value_json = jsonencode([
		{ "somebody" = { "id" = "somebody" } },
		{ "anybody" = { "id" = "anybody" } }
	])
}

