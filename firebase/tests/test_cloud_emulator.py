"""Real Firestore transactions with injected identities; no live cloud calls."""
import hashlib
import importlib
import json
import os
from pathlib import Path
import sys
import unittest
import uuid
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"functions"))


@unittest.skipUnless(os.environ.get("FIRESTORE_EMULATOR_HOST"),"Firestore emulator required")
class CloudApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import faulthandler
        faulthandler.dump_traceback_later(30, exit=True)
        cls.addClassCleanup(faulthandler.cancel_dump_traceback_later)
        os.environ["GCLOUD_PROJECT"]="demo-ecocapture"
        os.environ["NO_PROXY"] = "localhost,127.0.0.1,::1"
        os.environ["STORAGE_EMULATOR_HOST"] = "http://" + os.environ["FIREBASE_STORAGE_EMULATOR_HOST"]
        os.environ["FIREBASE_CONFIG"]=json.dumps({"projectId":"demo-ecocapture","storageBucket":"demo-ecocapture.appspot.com"})
        from google.auth.credentials import AnonymousCredentials
        credentials = patch('google.auth.default', return_value=(AnonymousCredentials(), 'demo-ecocapture'))
        credentials.start(); cls.addClassCleanup(credentials.stop)
        from google.auth.credentials import AnonymousCredentials
        credentials = patch('google.auth.default', return_value=(AnonymousCredentials(), 'demo-ecocapture'))
        credentials.start(); cls.addClassCleanup(credentials.stop)
        cls.main=importlib.import_module("main")
        from flask import Flask
        app=Flask(__name__)
        def route(route):
            from flask import request
            return cls.main.api(request)
        app.add_url_rule('/api/<path:route>',view_func=route,methods=['GET','POST'])
        cls.client=app.test_client()
    def setUp(self):
        self.study='s'+uuid.uuid4().hex[:12]
        self.uid=str(uuid.uuid4());self.snapshot=str(uuid.uuid4())
        self.db=self.main.db()
        self.db.collection('studies').document(self.study).set({'enabled':True,'key_versions':['a','b'],'recipients':['x','y']})
        self.db.collection('installations').document(self.uid).set({'enabled':True,'uid':self.uid,'study_id':self.study,'participant_code':'P014'})
        self.identity=patch.object(self.main,'firebase_identity',return_value={'uid':self.uid})
        self.identity.start();self.addCleanup(self.identity.stop)
        self.body={'snapshot_id':self.snapshot,'event_id':str(uuid.uuid4()),'recorded_at_epoch_ms':1000,'size_bytes':3,
                   'ciphertext_sha256':'a'*64,'manifest_sha256':'b'*64,'key_versions':['a','b']}
    def post(self,route,body):return self.client.post('/api/'+route,json=body)
    def test_registration_is_idempotent_and_owner_scoped(self):
        a=self.post('register',self.body);self.assertEqual(200,a.status_code,a.data)
        self.assertEqual(a.json,self.post('register',self.body).json)
        self.assertEqual(409,self.post('register',{**self.body,'ciphertext_sha256':'c'*64}).status_code)
        self.db.collection('installations').document(self.uid).update({'enabled':False})
        self.assertEqual(403,self.post('register',self.body).status_code)
    def test_qr_cannot_be_claimed_by_another_installation(self):
        code=hashlib.sha256(self.uid.encode()).hexdigest()[:43]
        self.db.collection('enrolments').document(hashlib.sha256(code.encode()).hexdigest()).set({
            'enabled':True,'expires_at':9999999999,'installation_id':None,'study_id':self.study,'participant_code':'P014'})
        with patch.object(self.main.auth,'create_custom_token',return_value=b'synthetic-token'):
            body={'code':code,'installation_id':self.uid}
            self.assertEqual(200,self.post('enrol',body).status_code)
            self.assertEqual(200,self.post('enrol',body).status_code)
            self.assertEqual(403,self.post('enrol',{**body,'installation_id':str(uuid.uuid4())}).status_code)
    def test_researcher_study_isolation_and_listing(self):
        self.post('register',self.body)
        with patch.object(self.main,'researcher',return_value=[self.study]):
            result=self.client.get('/api/catalogue?study='+self.study)
            self.assertEqual(200,result.status_code,result.data)
            self.assertEqual(1,len(result.json['items']))
            self.assertNotIn('owner_uid',result.json['items'][0])
            self.assertEqual(403,self.client.get('/api/catalogue?study=unassigned').status_code)
    def test_catalogue_paginates_without_repeating_snapshots(self):
        self.post('register',self.body)
        original=self.db.collection('packages').document(self.snapshot).get().to_dict()
        batch=self.db.batch()
        for _ in range(100):
            snapshot=str(uuid.uuid4())
            batch.create(self.db.collection('packages').document(snapshot),{**original,'snapshot_id':snapshot})
        batch.commit()
        with patch.object(self.main,'researcher',return_value=[self.study]):
            first=self.client.get('/api/catalogue?study='+self.study)
            self.assertEqual(200,first.status_code,first.data)
            self.assertEqual(100,len(first.json['items']))
            second=self.client.get('/api/catalogue?study='+self.study+'&after='+first.json['next'])
            self.assertEqual(200,second.status_code,second.data)
            self.assertEqual(1,len(second.json['items']))
            self.assertNotIn(second.json['items'][0]['snapshot_id'],{row['snapshot_id'] for row in first.json['items']})
    def test_participant_cannot_access_researcher_or_worker_routes(self):
        self.assertEqual(403,self.client.get('/api/catalogue?study='+self.study).status_code)
        with patch.dict(os.environ,{'ECO_WORKER_EMAIL':'receiver@example.iam.gserviceaccount.com',
                'ECO_WORKER_STUDY':self.study,'ECO_WORKER_AUDIENCE':'https://example.test/api'}),\
                patch.object(self.main.id_token,'verify_oauth2_token',return_value={'email':'other@example.test','email_verified':True}):
            self.assertEqual(403,self.client.get('/api/pending?study='+self.study,headers={'Authorization':'Bearer synthetic'}).status_code)
    def test_unauthenticated_status_denied(self):
        self.identity.stop()
        self.assertEqual(401,self.client.get('/api/profile').status_code)
    def test_completion_checks_cipher_hash_and_durable_worker_receipt(self):
        data=b'abc';self.body['ciphertext_sha256']=hashlib.sha256(data).hexdigest()
        self.assertEqual(200,self.post('register',self.body).status_code)
        package=self.db.collection('packages').document(self.snapshot).get().to_dict()
        bucket=self.main.storage.bucket();blob=bucket.blob(package['object_path']);blob.upload_from_string(data)
        completed=self.post('complete',{'snapshot_id':self.snapshot})
        self.assertEqual(200,completed.status_code,completed.data)
        self.assertEqual('AWAITING_GB10',completed.json['status'])
        receipt={'snapshot_id':self.snapshot,'ciphertext_sha256':self.body['ciphertext_sha256'],'status':'VERIFIED_GB10',
                 'gb10_path':f"{self.study}/P014/1970-01-01/{self.body['event_id']}/{self.snapshot}",'error_code':None}
        with patch.object(self.main,'worker',return_value=self.study):
            self.assertEqual(200,self.post('receipt',receipt).status_code)
            self.assertEqual(200,self.post('receipt',receipt).status_code)
            bad={**receipt,'status':'NEEDS_ATTENTION','gb10_path':None,'error_code':'RECEIVE_FAILED'}
            self.assertEqual(409,self.post('receipt',bad).status_code)
        self.assertFalse(self.client.get('/api/status/'+self.snapshot).json['archived'])

    @unittest.skipUnless(os.environ.get('ECO_CRYPTO_INTEROP'), 'Synthetic Go encryption harness not configured')
    def test_encrypted_package_through_cloud_storage_and_real_receiver(self):
        import subprocess
        import tempfile
        sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'server'))
        from ecocapture_gateway.synthetic_fixture import files
        from ecocapture_firebase.receiver import Receiver
        from ecocapture_firebase.cli import Cloud
        source=files(self.body['event_id'],self.snapshot)
        event=json.loads(source['event.json']);event['study_participant_code']='P014'
        source['event.json']=json.dumps(event).encode()
        manifest=json.loads(source['manifest.json'])
        manifest['files'][1].update(size_bytes=len(source['event.json']),sha256=hashlib.sha256(source['event.json']).hexdigest())
        source['manifest.json']=json.dumps(manifest).encode()
        test=self
        class Bridge:
            bucket=test.main.storage.bucket()
            download=Cloud.download
            def pending(self, study):
                result=test.client.get('/api/pending?study='+study)
                test.assertEqual(200,result.status_code,result.data)
                return result.json['items']
            def receipt(self, body):
                result=test.post('receipt',body)
                test.assertEqual(200,result.status_code,result.data)
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);bundle=root/'source';bundle.mkdir()
            for name,data in source.items():(bundle/name).write_bytes(data)
            encrypted=root/'encrypted'
            subprocess.run([os.environ['ECO_CRYPTO_INTEROP'],str(bundle),str(encrypted)],check=True,capture_output=True,timeout=20)
            cipher=(encrypted/'approved-event.tar.age').read_bytes()
            self.body.update(recorded_at_epoch_ms=1000,size_bytes=len(cipher),ciphertext_sha256=hashlib.sha256(cipher).hexdigest(),manifest_sha256=hashlib.sha256(source['manifest.json']).hexdigest())
            registered=self.post('register',self.body);self.assertEqual(200,registered.status_code,registered.data)
            self.main.storage.bucket().blob(registered.json['object_path']).upload_from_string(cipher)
            complete=self.post('complete',{'snapshot_id':self.snapshot});self.assertEqual(200,complete.status_code,complete.data)
            age=Path(__file__).resolve().parents[2]/'tools/phase3-protocol/artifacts/age.exe'
            receiver=Receiver(root/'received',self.study,age,encrypted/'gb10.agekey',Bridge())
            try:
                with patch.object(self.main,'worker',return_value=self.study):
                    receiver.once();receiver.once()
                status=self.client.get('/api/status/'+self.snapshot)
                self.assertEqual('VERIFIED_GB10',status.json['status'])
                self.assertFalse(status.json['archived'])
                self.assertEqual(source['video.mp4'],(receiver.root/status.json['gb10_path']/'video.mp4').read_bytes())
                self.assertEqual(1,receiver.db.execute('SELECT COUNT(*) FROM receipts').fetchone()[0])
            finally:receiver.close()


if __name__=='__main__':unittest.main()
