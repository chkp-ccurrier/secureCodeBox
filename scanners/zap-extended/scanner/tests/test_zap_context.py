from unittest.mock import MagicMock, Mock
from unittest.mock import patch
from unittest import TestCase


from scbzapv2.zap_configuration import ZapConfiguration
from scbzapv2.zap_context import ZapConfigureContext

class ZapScannerTests(TestCase):

    def test_always_passes(self):
        self.assertTrue(True)

