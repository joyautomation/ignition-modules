def doPost(request, session):
	# One endpoint, dispatched on "op", so the Go harness needs a single URL. Access is gated in config.json
	# (require-auth + the Administrator role), not here: this grants nothing a gateway login doesn't.
	#
	# Everything is a thin skin over system.tag.*, on purpose. The tests should observe and poke the gateway the
	# way a project script or the Designer does, not through a side door into the module.
	import system
	from java.util import Date

	body = request['data']
	if not isinstance(body, dict):
		body = system.util.jsonDecode(request['postData'])
	op = body.get('op')

	def plain(v):
		# JSON-safe view of whatever the tag system hands back
		if v is None or isinstance(v, (bool, int, long, float, basestring)):
			return v
		if isinstance(v, Date):
			return v.getTime()
		if isinstance(v, dict):
			return dict((str(k), plain(x)) for k, x in v.items())
		try:
			return [plain(x) for x in v]
		except TypeError:
			return str(v)

	def qv(q):
		ts = q.timestamp
		return {
			'value': plain(q.value),
			'quality': str(q.quality),
			'good': q.quality.isGood(),
			'timestamp': ts.getTime() if ts is not None else None,
		}

	if op == 'nav':
		# Reads the gateway's own navigation model from inside the gateway, so a module's registered page can be
		# verified without a browser session. Test support only.
		from com.inductiveautomation.ignition.gateway import IgnitionGateway
		model = IgnitionGateway.get().getWebResourceManager().getNavigationModel()
		sections = []
		for section in model.getSections():
			categories = []
			for category in section.getCategories():
				pages = []
				for page in category.pages():
					mount = page.mount()
					types = []
					try:
						for rt in page.associatedResourceTypes():
							types.append(str(rt))
					except Exception:
						pass
					pages.append({
						'label': str(page.label()),
						'url': str(mount.url()) if mount is not None else None,
						'permission': str(page.requiredPermission()),
						'resourceTypes': types,
					})
				categories.append({'key': str(category.key()), 'label': str(category.label()), 'pages': pages})
			sections.append({'label': str(section.getLabel()), 'categories': categories})
		return {'json': {'sections': sections}}

	if op == 'modules':
		# Module descriptors as the gateway parsed them, so a test can assert what module.xml actually said.
		from com.inductiveautomation.ignition.gateway import IgnitionGateway
		out = []
		for m in IgnitionGateway.get().getModuleManager().getModules():
			# the manager hands back a wrapper; the descriptor is on it under one of these names
			info = None
			for accessor in ('getModuleInfo', 'getInfo', 'getDescriptor'):
				if hasattr(m, accessor):
					info = getattr(m, accessor)()
					break
			if info is None:
				return {'json': {'error': 'no descriptor accessor on %s; has: %s'
					% (type(m).__name__, [a for a in dir(m) if a.startswith('get')][:25])}}
			out.append({
				'id': str(info.getId()),
				'name': str(info.getName()),
				'version': str(info.getVersion()),
				'vendorName': str(info.getVendorName()) if info.getVendorName() is not None else None,
				'vendorContactInfo': str(info.getVendorContactInfo()) if info.getVendorContactInfo() is not None else None,
			})
		return {'json': {'modules': out}}

	if op == 'resourcetypes':
		from com.inductiveautomation.ignition.gateway import IgnitionGateway
		reg = IgnitionGateway.get().getConfigurationManager().getResourceTypeMetaRegistry()
		out = []
		for meta in reg.getAllTypes():
			out.append(str(meta.getResourceType()))
		return {'json': {'types': sorted(out)}}

	if op == 'ping':
		return {'json': {'ok': True, 'time': system.date.now().getTime()}}

	if op == 'read':
		return {'json': {'values': [qv(q) for q in system.tag.readBlocking(body['paths'])]}}

	if op == 'write':
		results = system.tag.writeBlocking(body['paths'], body['values'], 10000)
		return {'json': {'results': [str(r) for r in results], 'good': [r.isGood() for r in results]}}

	if op == 'config':
		configs = system.tag.getConfiguration(body['path'], bool(body.get('recursive', False)))
		return {'json': {'configs': plain(configs)}}

	if op == 'configure':
		# The same call a person's edit ends up as: properties land in the tag's user layer.
		results = system.tag.configure(body['basePath'], body['tags'], body.get('collisionPolicy', 'm'))
		return {'json': {'results': [str(r) for r in results], 'good': [r.isGood() for r in results]}}

	if op == 'browse':
		results = system.tag.browse(body['path'], body.get('filter', {}))
		return {'json': {'results': [
			{'name': str(r['name']), 'path': str(r['fullPath']), 'tagType': str(r['tagType']),
			 'hasChildren': bool(r['hasChildren'])} for r in results.getResults()]}}

	if op == 'delete':
		results = system.tag.deleteTags(body['paths'])
		return {'json': {'results': [str(r) for r in results]}}

	if op == 'history':
		ds = system.tag.queryTagHistory(
			paths=body['paths'],
			startDate=Date(long(body['start'])),
			endDate=Date(long(body['end'])),
			returnSize=-1,
			includeBoundingValues=bool(body.get('bounding', False)),
			noInterpolation=True,
			ignoreBadQuality=bool(body.get('ignoreBad', False)),
			returnFormat='Tall',
		)
		rows = []
		for i in range(ds.getRowCount()):
			rows.append({
				'path': str(ds.getValueAt(i, 'path')),
				'value': plain(ds.getValueAt(i, 'value')),
				'quality': str(ds.getValueAt(i, 'quality')),
				'timestamp': ds.getValueAt(i, 'timestamp').getTime(),
			})
		return {'json': {'rows': rows}}

	return {'json': {'error': 'unknown op: %s' % op}}
