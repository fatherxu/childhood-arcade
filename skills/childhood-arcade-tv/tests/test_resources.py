import sys, unittest
from pathlib import Path
from unittest.mock import Mock, patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import resources
from resources import install_pack, InsufficientSpace
ENTRY={'name':'game.zip','sha256':'a'*64,'bytes':1024,'source':'/tmp/game.zip'}
def client():
 c=Mock();c.serial='test';c.shell.return_value='package:/data/app/org.childhood.arcade-1/base.apk';c.run.return_value=('','',0);c.probe.return_value={'free_bytes':100*1024*1024};return c
class ResourceTests(unittest.TestCase):
 @patch.object(resources,'resource_tool',return_value={'verified':False,'bytes':0})
 def test_pending_resources_reject_when_reported_space_is_small(self,tool):
  c=client()
  with self.assertRaises(InsufficientSpace):install_pack(c,'org.childhood.arcade',[ENTRY],lambda message:None,{},free_bytes=1024)
  tool.assert_called_once();c.probe.assert_not_called();c.run.assert_not_called()
 @patch.object(resources,'resource_tool')
 def test_reported_free_space_is_reused_without_probe(self,tool):
  tool.side_effect=[{'verified':False,'bytes':0},{'verified':True,'sha256':ENTRY['sha256'],'bytes':ENTRY['bytes']}]
  c=client();report={}
  install_pack(c,'org.childhood.arcade',[ENTRY],lambda message:None,report,free_bytes=64*1024*1024)
  c.probe.assert_not_called();c.run.assert_called_once();self.assertTrue(report['resources'][0]['verified'])
 @patch.object(resources,'resource_tool')
 def test_probe_supplies_space_when_unknown(self,tool):
  tool.side_effect=[{'verified':False,'bytes':0},{'verified':True,'sha256':ENTRY['sha256'],'bytes':ENTRY['bytes']}]
  c=client()
  install_pack(c,'org.childhood.arcade',[ENTRY],lambda message:None,{},free_bytes=None)
  c.probe.assert_called_once()
if __name__=='__main__':unittest.main()
