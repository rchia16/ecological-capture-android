// Read-only preflight using the Firebase CLI's authenticated session.
// Credentials stay inside the CLI library; never print accounts, tokens or raw errors.
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const auth=require('./dashboard/node_modules/firebase-tools/lib/auth.js');
const {requireAuth}=require('./dashboard/node_modules/firebase-tools/lib/requireAuth.js');
const {Client}=require('./dashboard/node_modules/firebase-tools/lib/apiv2.js');
const project='ecological-capture';
const options={project,nonInteractive:true};
const account=auth.selectAccount(undefined,process.cwd());
if(account)auth.setActiveAccount(options,account);
await requireAuth(options);
const checks=[
  ['project','https://firebase.googleapis.com','/v1beta1/projects/'+project,
    body=>({projectId:body.projectId,projectNumber:body.projectNumber,state:body.state,resources:body.resources})],
  ['buckets','https://storage.googleapis.com','/storage/v1/b?project='+project,
    body=>({buckets:(body.items??[]).map(row=>({name:row.name,location:row.location,locationType:row.locationType,storageClass:row.storageClass}))})],
  ['billing','https://cloudbilling.googleapis.com','/v1/projects/'+project+'/billingInfo',
    body=>({billingEnabled:body.billingEnabled===true})],
  ['hosting','https://firebasehosting.googleapis.com','/v1beta1/projects/'+project+'/sites',
    body=>({sites:(body.sites??[]).map(row=>({name:row.name,defaultUrl:row.defaultUrl}))})],
  ['authentication','https://identitytoolkit.googleapis.com','/admin/v2/projects/'+project+'/config',
    body=>({configured:true,authorisedDomains:body.authorizedDomains??[],anonymousEnabled:body.signIn?.anonymous?.enabled===true})]
];
const output=await Promise.all(checks.map(async([name,origin,path,select])=>{
  try{
    const result=await new Client({urlPrefix:origin}).get(path);
    return {check:name,status:'ok',...select(result.body)};
  }catch(error){return {check:name,status:'unavailable',httpStatus:error.status??error.context?.response?.statusCode??null};}
}));
console.log(JSON.stringify({project,checks:output},null,2));
