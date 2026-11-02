import hashlib
import json
from unittest.mock import patch
import tempfile
import unittest
import zipfile
from pathlib import Path
from prepare_release_apks import ABIS, PACKAGE, stage, validate_native_outputs


class ReleaseApksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def apk(self, name, abis, libraries=('libplayer.so', 'libdecoder.so')):
        path = self.root / f'{name}.apk'
        with zipfile.ZipFile(path, 'w') as archive:
            for abi in abis:
                for library in libraries:
                    archive.writestr(f'lib/{abi}/{library}', b'native')
        return path

    def outputs(self):
        return {'universal': self.apk('universal', ABIS), **{abi: self.apk(abi, [abi]) for abi in ABIS}}

    def test_complete_architecture_outputs(self):
        validate_native_outputs(self.outputs())

    def test_wrong_architecture_rejected(self):
        outputs = self.outputs()
        outputs['x86'] = self.apk('wrong', ['x86_64'])
        with self.assertRaises(ValueError):
            validate_native_outputs(outputs)

    def test_missing_decoder_rejected(self):
        outputs = self.outputs()
        outputs['arm64-v8a'] = self.apk('missing', ['arm64-v8a'], ['libplayer.so'])
        with self.assertRaises(ValueError):
            validate_native_outputs(outputs)

    def test_incomplete_universal_rejected(self):
        outputs = self.outputs()
        outputs['universal'] = self.apk('partial', ['arm64-v8a'])
        with self.assertRaises(ValueError):
            validate_native_outputs(outputs)

    def metadata(self):
        outputs = self.outputs()
        elements = [{'filters': [] if abi == 'universal' else [{'filterType': 'ABI', 'value': abi}],
                     'outputFile': apk.name} for abi, apk in outputs.items()]
        (self.root / 'output-metadata.json').write_text(json.dumps({'elements': elements}))

    def test_checksums_match_staged_bytes_and_use_portable_newlines(self):
        self.metadata()
        with patch('prepare_release_apks.identity', return_value=(PACKAGE, 10007, '0.1.0-beta.5')), \
             patch('prepare_release_apks.certificate', return_value='permanent-signer'):
            assets = stage(self.root, self.root / 'staged', '0.1.0-beta.5', 'signer', 'aapt')
        sums = next(p for p in assets if p.name == 'SHA256SUMS.txt').read_bytes()
        self.assertNotIn(b'\r', sums)
        for line in sums.decode().splitlines():
            digest, name = line.split('  ', 1)
            self.assertEqual(digest, hashlib.sha256((self.root / 'staged' / name).read_bytes()).hexdigest())
        names = [p.name for p in assets if p.suffix == '.apk']
        self.assertLess(names.index('Nuvio-ReSeq-v0.1.0-beta.5-x86.apk'), names.index('Nuvio-ReSeq-v0.1.0-beta.5-x86_64.apk'))

    def test_differently_signed_variant_is_rejected(self):
        self.metadata()
        with patch('prepare_release_apks.identity', return_value=(PACKAGE, 10007, '0.1.0-beta.5')), \
             patch('prepare_release_apks.certificate', side_effect=['permanent-signer', 'different-signer']):
            with self.assertRaisesRegex(ValueError, 'signing'):
                stage(self.root, self.root / 'staged', '0.1.0-beta.5', 'signer', 'aapt')
